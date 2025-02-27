/**
 * Copyright 2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */

package com.github.ambry.store;

import com.codahale.metrics.MetricRegistry;
import com.github.ambry.account.AccountService;
import com.github.ambry.clustermap.ClusterMap;
import com.github.ambry.clustermap.ClusterParticipant;
import com.github.ambry.clustermap.DataNodeId;
import com.github.ambry.clustermap.DiskId;
import com.github.ambry.clustermap.PartitionId;
import com.github.ambry.clustermap.PartitionStateChangeListener;
import com.github.ambry.clustermap.ReplicaId;
import com.github.ambry.clustermap.ReplicaState;
import com.github.ambry.clustermap.ReplicaStatusDelegate;
import com.github.ambry.clustermap.ReplicaSyncUpManager;
import com.github.ambry.clustermap.StateModelListenerType;
import com.github.ambry.clustermap.StateTransitionException;
import com.github.ambry.config.DiskManagerConfig;
import com.github.ambry.config.StoreConfig;
import com.github.ambry.server.ServerErrorCode;
import com.github.ambry.server.StoreManager;
import com.github.ambry.utils.Time;
import com.github.ambry.utils.Utils;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.ambry.clustermap.StateTransitionException.TransitionErrorCode.*;
import static com.github.ambry.store.LogSegment.*;


/**
 * The storage manager that handles all the stores on this node. The stores on each disk are handled by a
 * {@link DiskManager}
 */
public class StorageManager implements StoreManager {
  protected final ConcurrentHashMap<PartitionId, DiskManager> partitionToDiskManager = new ConcurrentHashMap<>();
  protected final ConcurrentHashMap<DiskId, DiskManager> diskToDiskManager = new ConcurrentHashMap<>();
  protected final ConcurrentHashMap<String, ReplicaId> partitionNameToReplicaId = new ConcurrentHashMap<>();
  private final List<ReplicaStatusDelegate> replicaStatusDelegates;
  private final Set<String> stoppedReplicas = new HashSet<>();
  private final StorageManagerMetrics metrics;
  private final Time time;
  private final StoreConfig storeConfig;
  private final DiskManagerConfig diskManagerConfig;
  private final ScheduledExecutorService scheduler;
  private final StoreMetrics storeMainMetrics;
  private final StoreMetrics storeUnderCompactionMetrics;
  private final StoreKeyFactory keyFactory;
  private final ClusterMap clusterMap;
  private final DataNodeId currentNode;
  private final MessageStoreRecovery recovery;
  private final MessageStoreHardDelete hardDelete;
  private final List<ClusterParticipant> clusterParticipants;
  private final ClusterParticipant primaryClusterParticipant;
  private final ReplicaSyncUpManager replicaSyncUpManager;
  private final Set<String> unexpectedDirs = new HashSet<>();
  private static final Logger logger = LoggerFactory.getLogger(StorageManager.class);
  private final AccountService accountService;

  /**
   * Constructs a {@link StorageManager}
   * @param storeConfig the settings for store configuration.
   * @param diskManagerConfig the settings for disk manager configuration
   * @param scheduler the {@link ScheduledExecutorService} for executing background tasks.
   * @param registry the {@link MetricRegistry} used for store-related metrics.
   * @param keyFactory the {@link StoreKeyFactory} for parsing store keys.
   * @param clusterMap the {@link ClusterMap} instance to use.
   * @param dataNodeId the {@link DataNodeId} of current node.
   * @param hardDelete the {@link MessageStoreHardDelete} instance to use.
   * @param clusterParticipants a list of {@link ClusterParticipant}(s) that allows storage manager to interact with
   *                            cluster managers (i.e Helix). In most cases there is only one participant. However, in
   *                            edge case like migrating Ambry from one zk cluster to the other, it requires server to
   *                            temporarily participate into two clusters and therefore we need two participants.
   * @param time the {@link Time} instance to use.
   * @param recovery the {@link MessageStoreRecovery} instance to use.
   * @param accountService the {@link AccountService} instance to use.
   */
  public StorageManager(StoreConfig storeConfig, DiskManagerConfig diskManagerConfig,
      ScheduledExecutorService scheduler, MetricRegistry registry, StoreKeyFactory keyFactory, ClusterMap clusterMap,
      DataNodeId dataNodeId, MessageStoreHardDelete hardDelete, List<ClusterParticipant> clusterParticipants, Time time,
      MessageStoreRecovery recovery, AccountService accountService) throws StoreException {
    verifyConfigs(storeConfig, diskManagerConfig);
    this.storeConfig = storeConfig;
    this.diskManagerConfig = diskManagerConfig;
    this.scheduler = scheduler;
    this.time = time;
    this.keyFactory = keyFactory;
    this.recovery = recovery;
    this.hardDelete = hardDelete;
    this.accountService = accountService;
    this.clusterMap = clusterMap;
    this.clusterParticipants = clusterParticipants;
    // The first participant (if there are multiple) in clusterParticipants list is considered primary participant by default.
    // Only primary participant should take actions in storage manager when state transition is invoked by Helix controller.
    primaryClusterParticipant =
        clusterParticipants == null || clusterParticipants.isEmpty() ? null : clusterParticipants.get(0);
    replicaSyncUpManager =
        primaryClusterParticipant == null ? null : primaryClusterParticipant.getReplicaSyncUpManager();
    currentNode = dataNodeId;
    metrics = new StorageManagerMetrics(registry);
    storeMainMetrics = new StoreMetrics(registry);
    storeUnderCompactionMetrics = new StoreMetrics("UnderCompaction", registry);
    if (clusterParticipants != null) {
      replicaStatusDelegates = new ArrayList<>();
      for (ClusterParticipant clusterParticipant : clusterParticipants) {
        ReplicaStatusDelegate replicaStatusDelegate = new ReplicaStatusDelegate(clusterParticipant);
        replicaStatusDelegates.add(replicaStatusDelegate);
        stoppedReplicas.addAll(replicaStatusDelegate.getStoppedReplicas());
      }
    } else {
      replicaStatusDelegates = null;
    }
    Map<DiskId, List<ReplicaId>> diskToReplicaMap = new HashMap<>();
    for (ReplicaId replica : clusterMap.getReplicaIds(dataNodeId)) {
      DiskId disk = replica.getDiskId();
      diskToReplicaMap.computeIfAbsent(disk, key -> new ArrayList<>()).add(replica);
      partitionNameToReplicaId.put(replica.getPartitionId().toPathString(), replica);
    }
    for (Map.Entry<DiskId, List<ReplicaId>> entry : diskToReplicaMap.entrySet()) {
      DiskId disk = entry.getKey();
      List<ReplicaId> replicasForDisk = entry.getValue();
      DiskManager diskManager =
          new DiskManager(disk, replicasForDisk, storeConfig, diskManagerConfig, scheduler, metrics, storeMainMetrics,
              storeUnderCompactionMetrics, keyFactory, recovery, hardDelete, replicaStatusDelegates, stoppedReplicas,
              time, accountService);
      diskToDiskManager.put(disk, diskManager);
      for (ReplicaId replica : replicasForDisk) {
        partitionToDiskManager.put(replica.getPartitionId(), diskManager);
      }
    }
  }

  /**
   * Verify that the {@link StoreConfig} and {@link DiskManagerConfig} has valid settings.
   * @param storeConfig the {@link StoreConfig} to verify.
   * @param diskManagerConfig the {@link DiskManagerConfig} to verify
   * @throws StoreException if the {@link StoreConfig} or {@link DiskManagerConfig} is invalid.
   */
  private void verifyConfigs(StoreConfig storeConfig, DiskManagerConfig diskManagerConfig) throws StoreException {
    /* NOTE: We must ensure that the store never performs hard deletes on the part of the log that is not yet flushed.
       We do this by making sure that the retention period for deleted messages (which determines the end point for hard
       deletes) is always greater than the log flush period. */
    if (storeConfig.storeEnableHardDelete && storeConfig.storeDeletedMessageRetentionMinutes
        < TimeUnit.SECONDS.toMinutes(storeConfig.storeDataFlushIntervalSeconds) + 1) {
      throw new StoreException(
          "Message retention hours must be greater than the store flush interval period when hard delete is enabled",
          StoreErrorCodes.Initialization_Error);
    }
    if (diskManagerConfig.diskManagerReserveFileDirName.length() == 0) {
      throw new StoreException("Reserve file directory name is empty", StoreErrorCodes.Initialization_Error);
    }
  }

  /**
   * Start the {@link DiskManager}s for all disks on this node.
   * @throws InterruptedException
   */
  public void start() throws InterruptedException, StoreException {
    long startTimeMs = time.milliseconds();
    try {
      logger.info("Starting storage manager");
      List<Thread> startupThreads = new ArrayList<>();
      for (final DiskManager diskManager : diskToDiskManager.values()) {
        Thread thread = Utils.newThread("disk-manager-startup-" + diskManager.getDisk(), () -> {
          try {
            diskManager.start();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Disk manager startup thread interrupted for disk {}", diskManager.getDisk(), e);
          }
        }, false);
        thread.start();
        startupThreads.add(thread);
      }
      for (Thread startupThread : startupThreads) {
        startupThread.join();
      }
      metrics.initializeCompactionThreadsTracker(this, diskToDiskManager.size());
      metrics.initializeHostUtilizationTracker(this);
      if (clusterParticipants != null) {
        clusterParticipants.forEach(clusterParticipant -> {
          clusterParticipant.registerPartitionStateChangeListener(StateModelListenerType.StorageManagerListener,
              new PartitionStateChangeListenerImpl(clusterParticipant == primaryClusterParticipant));
          clusterParticipant.setInitialLocalPartitions(partitionNameToReplicaId.keySet());
        });
      }
      diskToDiskManager.values().forEach(diskManager -> unexpectedDirs.addAll(diskManager.getUnexpectedDirs()));
      logger.info("Starting storage manager complete");
    } finally {
      metrics.storageManagerStartTimeMs.update(time.milliseconds() - startTimeMs);
    }
  }

  @Override
  public Store getStore(PartitionId id) {
    return getStore(id, false);
  }

  /**
   * @param id the {@link PartitionId} to find the store for.
   * @param skipStateCheck whether to skip checking state of the store. if true, it also returns store that is not started yet.
   * @return the {@link Store} corresponding to the given {@link PartitionId}, or {@code null} if no store was found for
   *         that partition, or that store was not started.
   */
  public Store getStore(PartitionId id, boolean skipStateCheck) {
    DiskManager diskManager = partitionToDiskManager.get(id);
    return diskManager != null ? diskManager.getStore(id, skipStateCheck) : null;
  }

  @Override
  public ReplicaId getReplica(String partitionName) {
    return partitionNameToReplicaId.get(partitionName);
  }

  @Override
  public Collection<PartitionId> getLocalPartitions() {
    return Collections.unmodifiableCollection(partitionToDiskManager.keySet());
  }

  @Override
  public ServerErrorCode checkLocalPartitionStatus(PartitionId partition, ReplicaId localReplica) {
    if (getStore(partition) == null) {
      if (localReplica != null) {
        // check stores on the disk
        if (!isDiskAvailable(localReplica.getDiskId())) {
          return ServerErrorCode.Disk_Unavailable;
        } else {
          return ServerErrorCode.Replica_Unavailable;
        }
      } else {
        return ServerErrorCode.Partition_Unknown;
      }
    }
    return ServerErrorCode.No_Error;
  }

  /**
   * @param id the {@link PartitionId} to find the DiskManager for.
   * @return the {@link DiskManager} corresponding to the given {@link PartitionId}, or {@code null} if no DiskManager was found for
   *         that partition
   */
  DiskManager getDiskManager(PartitionId id) {
    return partitionToDiskManager.get(id);
  }

  /**
   * Check if a certain disk is available.
   * @param disk the {@link DiskId} to check.
   * @return {@code true} if the disk is available. {@code false} if not.
   */
  boolean isDiskAvailable(DiskId disk) {
    DiskManager diskManager = diskToDiskManager.get(disk);
    return diskManager != null && !diskManager.areAllStoresDown();
  }

  @Override
  public boolean scheduleNextForCompaction(PartitionId id) {
    DiskManager diskManager = partitionToDiskManager.get(id);
    return diskManager != null && diskManager.scheduleNextForCompaction(id);
  }

  @Override
  public boolean controlCompactionForBlobStore(PartitionId id, boolean enabled) {
    DiskManager diskManager = partitionToDiskManager.get(id);
    return diskManager != null && diskManager.controlCompactionForBlobStore(id, enabled);
  }

  /**
   * Shutdown the {@link DiskManager}s for the disks on this node.
   * @throws InterruptedException
   */
  public void shutdown() throws InterruptedException {
    long startTimeMs = time.milliseconds();
    try {
      logger.info("Shutting down storage manager");
      List<Thread> shutdownThreads = new ArrayList<>();
      for (final DiskManager diskManager : diskToDiskManager.values()) {
        Thread thread = Utils.newThread("disk-manager-shutdown-" + diskManager.getDisk(), () -> {
          try {
            diskManager.shutdown();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Disk manager shutdown thread interrupted for disk {}", diskManager.getDisk(), e);
          }
        }, false);
        thread.start();
        shutdownThreads.add(thread);
      }
      for (Thread shutdownThread : shutdownThreads) {
        shutdownThread.join();
      }
      metrics.deregisterCompactionThreadsTracker();
      metrics.deregisterHostUtilizationTracker();
      logger.info("Shutting down storage manager complete");
    } finally {
      metrics.storageManagerShutdownTimeMs.update(time.milliseconds() - startTimeMs);
    }
  }

  @Override
  public boolean addBlobStore(ReplicaId replica) {
    if (partitionToDiskManager.containsKey(replica.getPartitionId())) {
      logger.info("{} already exists in storage manager, rejecting adding store request", replica.getPartitionId());
      return false;
    }
    DiskManager diskManager = diskToDiskManager.computeIfAbsent(replica.getDiskId(), disk -> {
      DiskManager newDiskManager =
          new DiskManager(disk, Collections.emptyList(), storeConfig, diskManagerConfig, scheduler, metrics,
              storeMainMetrics, storeUnderCompactionMetrics, keyFactory, recovery, hardDelete, replicaStatusDelegates,
              stoppedReplicas, time, accountService);
      logger.info("Creating new DiskManager on {} for new added store", replica.getDiskId().getMountPath());
      try {
        newDiskManager.start();
      } catch (Exception e) {
        logger.error("Error while starting the new DiskManager for {}", disk.getMountPath(), e);
        return null;
      }
      return newDiskManager;
    });
    if (diskManager == null || !diskManager.addBlobStore(replica)) {
      logger.error("Failed to add new store into DiskManager");
      return false;
    }
    partitionToDiskManager.put(replica.getPartitionId(), diskManager);
    partitionNameToReplicaId.put(replica.getPartitionId().toPathString(), replica);
    logger.info("New store is successfully added into StorageManager");
    return true;
  }

  @Override
  public boolean startBlobStore(PartitionId id) {
    DiskManager diskManager = partitionToDiskManager.get(id);
    return diskManager != null && diskManager.startBlobStore(id);
  }

  @Override
  public boolean shutdownBlobStore(PartitionId id) {
    DiskManager diskManager = partitionToDiskManager.get(id);
    return diskManager != null && diskManager.shutdownBlobStore(id);
  }

  @Override
  public boolean removeBlobStore(PartitionId id) throws IOException, StoreException {
    DiskManager diskManager = partitionToDiskManager.get(id);
    if (diskManager == null) {
      logger.info("Store {} is not found in storage manager", id);
      return false;
    }
    if (!diskManager.removeBlobStore(id)) {
      logger.error("Fail to remove store {} from disk manager", id);
      return false;
    }
    partitionToDiskManager.remove(id);
    logger.info("Store {} is successfully removed from storage manager", id);
    return true;
  }

  @Override
  public List<PartitionId> setBlobStoreStoppedState(List<PartitionId> partitionIds, boolean markStop) {
    Map<DiskManager, List<PartitionId>> diskManagerToPartitionMap = new HashMap<>();
    List<PartitionId> failToUpdateStores = new ArrayList<>();
    for (PartitionId id : partitionIds) {
      DiskManager diskManager = partitionToDiskManager.get(id);
      if (diskManager != null) {
        diskManagerToPartitionMap.computeIfAbsent(diskManager, disk -> new ArrayList<>()).add(id);
      } else {
        failToUpdateStores.add(id);
      }
    }
    for (Map.Entry<DiskManager, List<PartitionId>> diskToPartitions : diskManagerToPartitionMap.entrySet()) {
      List<PartitionId> failList =
          diskToPartitions.getKey().setBlobStoreStoppedState(diskToPartitions.getValue(), markStop);
      failToUpdateStores.addAll(failList);
    }
    return failToUpdateStores;
  }

  /**
   * @return host usage percentage
   */
  double getHostPercentageUsedCapacity() {
    long totalDiskCapacity = 0;
    long totalDiskAvailableSpace = 0;

    for (DiskId diskId : diskToDiskManager.keySet()) {
      totalDiskCapacity += diskId.getRawCapacityInBytes();
      totalDiskAvailableSpace += diskId.getAvailableSpaceInBytes();
    }

    return (double) ((totalDiskCapacity - totalDiskAvailableSpace) / totalDiskCapacity) * 100;
  }

  /**
   * @return the number of compaction threads running.
   */
  int getCompactionThreadCount() {
    int count = 0;
    for (DiskManager diskManager : diskToDiskManager.values()) {
      if (diskManager.isCompactionExecutorRunning()) {
        count++;
      }
    }
    return count;
  }

  /**
   * Create a bootstrap file in given replica directory if the file is not present.
   * @param replica the {@link ReplicaId} whose directory should contain the bootstrap file.
   * @throws IOException
   */
  static void createBootstrapFileIfAbsent(ReplicaId replica) throws IOException {
    File bootstrapFile = new File(replica.getReplicaPath(), BlobStore.BOOTSTRAP_FILE_NAME);
    if (!bootstrapFile.exists()) {
      bootstrapFile.createNewFile();
    }
  }

  /**
   * Maybe delete the residual directory associated with removed replica.
   * @param partitionName name of replica that is already removed
   */
  private void maybeDeleteResidualDirectory(String partitionName) throws IOException {
    for (String unexpectedDir : unexpectedDirs) {
      String[] segments = unexpectedDir.split(File.separator);
      if (partitionName.equals(segments[segments.length - 1])) {
        logger.info("Deleting residual directory associated with store {}", partitionName);
        File dirToDelete = new File(unexpectedDir);
        try {
          Utils.deleteFileOrDirectory(dirToDelete);
        } catch (Exception e) {
          throw new IOException("Couldn't delete directory " + unexpectedDir, e);
        }
      }
    }
  }

  /**
   * Helper utility to add replicaId to partitionToDiskManager.
   * @param replicaId the {@link ReplicaId} whose partition and disk to use.
   */
  protected void updatePartitionToDiskManager(ReplicaId replicaId) {
    partitionToDiskManager.put(replicaId.getPartitionId(), diskToDiskManager.get(replicaId.getDiskId()));
  }

  /**
   * Getter utility for protected diskToDiskManager
   * @return diskToDiskManager
   */
  public ConcurrentHashMap<DiskId, DiskManager> getDiskToDiskManager() {
    return diskToDiskManager;
  }

  /**
   * Getter utility for protected parititontoDiskManager
   * @return parititontoDiskManager
   */
  public ConcurrentHashMap<PartitionId, DiskManager> getPartitionToDiskManager() {
    return partitionToDiskManager;
  }

  /**
   * Implementation of {@link PartitionStateChangeListener} to capture state changes and take actions accordingly.
   */
  private class PartitionStateChangeListenerImpl implements PartitionStateChangeListener {
    // We could be occasionally participating in multiple helix/zk clusters during migration from one ZK cluster to
    // another. Ideally, the actions in Storage manager for state changes (such as offline -> bootstrap, etc) should be
    // taken only for primary participant. But, in order to detect store failures and mark ERROR in helix state on both
    // helix clusters, we let the listener logic to be executed for both participants (PR: https://github.com/linkedin/ambry/pull/1550).
    // However, we need to make sure the internal state of store is only updated by primary cluster participant since
    // Replication Manager which only listens to primary participant also updates the internal store states. For such
    // needs, use this boolean.

    /**
     * Indicates whether it's a listener object for primary helix cluster-manager. Used to respond to state transition
     * messages from primary helix cluster, which is simply the first string in zkConnectStr separated by commas.
     * The word "participant" is being used to refer to both ambry-server node and helix at some places in the
     * code which is wrong. We don't want to fix all usages retroactively at this point,
     * but going forward please follow this convention below.
     * <p>
     * participant = ambry-server node that _participates_ in cluster management done by helix cluster-manager service
     * participant = _NOT_ helix cluster-manager service
     * <p>
     */
    private final boolean isPrimaryClusterManagerListener;
    PartitionStateChangeListener replicationManagerListener = null;
    PartitionStateChangeListener statsManagerListener = null;

    /**
     * Constructor
     * @param isPrimaryClusterManagerListener Indicates whether it's a listener object for primary helix cluster-manager
     */
    PartitionStateChangeListenerImpl(boolean isPrimaryClusterManagerListener) {
      this.isPrimaryClusterManagerListener = isPrimaryClusterManagerListener;
    }

    @Override
    public void onPartitionBecomeBootstrapFromOffline(String partitionName) {
      // check if partition exists on current node
      ReplicaId replica = partitionNameToReplicaId.get(partitionName);
      Store store = null;
      if (replica == null) {
        // there can be two scenarios:
        // 1. this is the first time to add new replica onto current node;
        // 2. last replica addition failed at some point before updating InstanceConfig in Helix
        // In either case, we should add replica to current node by calling "addBlobStore(ReplicaId replica)"
        ReplicaId replicaToAdd = clusterMap.getBootstrapReplica(partitionName, currentNode);
        if (replicaToAdd == null) {
          logger.error("No new replica found for partition {} in cluster map", partitionName);
          throw new StateTransitionException(
              "New replica " + partitionName + " is not found in clustermap for " + currentNode, ReplicaNotFound);
        }
        // Attempt to add store into storage manager. If store already exists on disk (but not in clustermap), make
        // sure old store of this replica is deleted (this store may be created in previous replica addition but failed
        // at some point). Then a brand new store associated with this replica should be created and started.
        if (!addBlobStore(replicaToAdd)) {
          // We have decreased the available disk space in HelixClusterManager#getDiskForBootstrapReplica. Increase it
          // back since addition of store failed.
          replicaToAdd.getDiskId().increaseAvailableSpaceInBytes(replicaToAdd.getCapacityInBytes());
          logger.error("Failed to add store {} into storage manager", partitionName);
          throw new StateTransitionException("Failed to add store " + partitionName + " into storage manager",
              ReplicaOperationFailure);
        }
        if (primaryClusterParticipant != null) {
          // update InstanceConfig in Helix
          try {
            if (!primaryClusterParticipant.updateDataNodeInfoInCluster(replicaToAdd, true)) {
              logger.error("Failed to add partition {} into InstanceConfig of current node", partitionName);
              throw new StateTransitionException("Failed to add partition " + partitionName + " into InstanceConfig",
                  StateTransitionException.TransitionErrorCode.HelixUpdateFailure);
            }
            logger.info("Partition {} is successfully added into InstanceConfig of current node", partitionName);
          } catch (IllegalStateException e) {
            throw new StateTransitionException(e.getMessage(),
                StateTransitionException.TransitionErrorCode.HelixUpdateFailure);
          }
        }
        // if addBlobStore succeeds, it is guaranteed that store is started and thus getStore result is not null.
        store = getStore(replicaToAdd.getPartitionId(), false);

        // note that partitionNameToReplicaId should be updated if addBlobStore succeeds, so replicationManager should be
        // able to get new replica from storageManager without querying Helix
      } else {
        // if the replica is already on current node, there are 4 cases need to discuss:
        // 1. replica was initially present in clustermap and this is a regular reboot.
        // 2. replica was dynamically added to this node but may fail during BOOTSTRAP -> STANDBY transition.
        // 3. replica is on current node but its disk is offline. The replica is not able to start.
        // 4. replica was initially present in clustermap but it's current being recreated due to disk failure before.

        // For case 1 and 2, OFFLINE -> BOOTSTRAP is complete, we leave remaining actions (if there any) to other transition.
        // For case 3, we should throw exception to make replica stay in ERROR state (thus, frontends won't pick this replica)
        // For case 4, we check it's current used capacity and put it in BOOTSTRAP state if necessary. This is to ensure
        //             it catches up with peers before serving PUT traffic (or being selected as LEADER)
        store = getStore(replica.getPartitionId(), false);
        if (store == null) {
          throw new StateTransitionException(
              "Store " + partitionName + " didn't start correctly, replica should be set to ERROR state",
              StoreNotStarted);
        }

        File decommissionFile = new File(replica.getReplicaPath(), BlobStore.DECOMMISSION_FILE_NAME);
        if (decommissionFile.exists()) {
          // Delete any decommission file if present.
          // During migration from semi-auto to full-auto, we observed that helix could issue state transitions from
          // Standby -> Inactive -> Offline -> Bootstrap -> Standby. We would have created decommission file during
          // Standby -> Inactive step of this process. Delete this file now since the same replica is being bootstrapped
          // again.
          decommissionFile.delete();
          logger.info("Old decommission file is deleted for replica {}", replica.getReplicaPath());
          ((BlobStore) store).setRecoverFromDecommission(false);
        }

        // if store's used capacity is less than or equal to header size, we create a bootstrap_in_progress file and force
        // it to stay in BOOTSTRAP state when catching up with peers.
        long storeUsedCapacity = store.getSizeInBytes();
        if (storeUsedCapacity <= HEADER_SIZE) {
          logger.info(
              "Store {} has used capacity {} less than or equal to {} bytes, consider it recently created and make it go through bootstrap process.",
              partitionName, storeUsedCapacity, HEADER_SIZE);
          try {
            createBootstrapFileIfAbsent(replica);
          } catch (IOException e) {
            logger.error("Failed to create bootstrap file for store {}", partitionName);
            throw new StateTransitionException("Failed to create bootstrap file for " + partitionName,
                ReplicaOperationFailure);
          }
        }
      }
      if (isPrimaryClusterManagerListener) {
        // Only update store state if this is a state transition for primary participant. Since replication Manager
        // which eventually moves this state to STANDBY/LEADER only listens to primary participant, store state gets
        // stuck in BOOTSTRAP if this is updated by second participant listener too
        ReplicaState currentState = store.getCurrentState();
        if (currentState != ReplicaState.LEADER && currentState != ReplicaState.STANDBY) {
          // Only set the current state to BOOTSTRAP when it's not LEADER or STANDBY
          store.setCurrentState(ReplicaState.BOOTSTRAP);
        }
      }
    }

    @Override
    public void onPartitionBecomeStandbyFromBootstrap(String partitionName) {
      // no op
    }

    @Override
    public void onPartitionBecomeLeaderFromStandby(String partitionName) {
      // no op
    }

    @Override
    public void onPartitionBecomeStandbyFromLeader(String partitionName) {
      // no op
    }

    @Override
    public void onPartitionBecomeInactiveFromStandby(String partitionName) {
      // check if partition exists on current node
      ReplicaId replica = partitionNameToReplicaId.get(partitionName);
      // if replica is null that means partition is not on current node (this shouldn't happen unless we use server admin
      // tool to remove the store before initiating decommission on this partition). We throw exception in this case.
      if (replica != null) {
        Store localStore = getStore(replica.getPartitionId(), true);
        if (localStore == null) {
          throw new StateTransitionException("Store " + partitionName + " is not found on current node",
              ReplicaNotFound);
        }
        if (localStore.isDisabled()) {
          // if store is disabled due to disk I/O error or by admin operation, we explicitly throw an exception to mark
          // partition in Helix ERROR state
          throw new StateTransitionException(
              "Store " + partitionName + " is already disabled due to I/O error or by " + "admin operation",
              ReplicaOperationFailure);
        }
        // 0. as long as local replica exists, we create a decommission file in its dir
        File decommissionFile = new File(replica.getReplicaPath(), BlobStore.DECOMMISSION_FILE_NAME);
        try {
          if (!decommissionFile.exists()) {
            // if not present, create one.
            decommissionFile.createNewFile();
            logger.info("Decommission file is created for replica {}", replica.getReplicaPath());
          }
        } catch (IOException e) {
          logger.error("IOException occurs when creating decommission file for replica " + partitionName, e);
          throw new StateTransitionException(
              "Couldn't create decommission file for replica " + replica.getReplicaPath(), ReplicaOperationFailure);
        }
        if (localStore.isStarted()) {
          // 1. set state to INACTIVE
          if (isPrimaryClusterManagerListener) {
            localStore.setCurrentState(ReplicaState.INACTIVE);
            logger.info("Store {} is set to INACTIVE", partitionName);
          }
          // 2. disable compaction on this store
          if (!controlCompactionForBlobStore(replica.getPartitionId(), false)) {
            logger.error("Failed to disable compaction on store {}", partitionName);
            // we set error code to ReplicaNotFound because that is the only reason why compaction may fail.
            throw new StateTransitionException("Couldn't disable compaction on replica " + replica.getReplicaPath(),
                ReplicaNotFound);
          }
          logger.info("Compaction is successfully disabled on store {}", partitionName);
        } else {
          // this may happen when the disk holding this store crashes (or store is stopped by server admin tool)
          throw new StateTransitionException("Store " + partitionName + " is not started", StoreNotStarted);
        }
      } else {
        throw new StateTransitionException("Replica " + partitionName + " is not found on current node",
            ReplicaNotFound);
      }
    }

    @Override
    public void onPartitionBecomeOfflineFromInactive(String partitionName) {
      // no op
    }

    @Override
    public void onPartitionBecomeDroppedFromOffline(String partitionName) {
      ReplicaId replica = partitionNameToReplicaId.get(partitionName);
      if (replica == null) {
        // During decommission, imagine an edge case where the node crashed immediately after removing replica from
        // InstanceConfig. So next time when this node is restarted, old replica is no longer present in Helix cluster
        // map. We still need to clean up file/directory on disk that is associated with removed replica.
        try {
          maybeDeleteResidualDirectory(partitionName);
        } catch (IOException e) {
          logger.error("Exception occurs when deleting residual dir of replica {} with error msg: {}", partitionName,
              e.getMessage());
          metrics.resumeDecommissionErrorCount.inc();
          throw new StateTransitionException("Failed to delete residual dir of store " + partitionName,
              ReplicaOperationFailure);
        }
        return;
      }
      Map<StateModelListenerType, PartitionStateChangeListener> partitionStateChangeListeners =
          primaryClusterParticipant == null ? new HashMap<>()
              : primaryClusterParticipant.getPartitionStateChangeListeners();
      replicationManagerListener = partitionStateChangeListeners.get(StateModelListenerType.ReplicationManagerListener);
      statsManagerListener = partitionStateChangeListeners.get(StateModelListenerType.StatsManagerListener);
      // get the store (skip the state check here, because probably the store is stopped in previous transition. Also,
      // the store could be started if it failed on decommission last time. Helix may directly reset it to OFFLINE
      // without stopping it)
      BlobStore store = (BlobStore) getStore(replica.getPartitionId(), true);

      // 1. Check if the store is recovering from decommission or directly transitioning from OFFLINE to DROPPED in
      // full-auto (i.e. if this replica has been reassigned to a different host when it is down, Helix may directly
      // transition the replica from OFFLINE -> DROPPED without going through OFFLINE -> BOOTSTRAP -> STANDBY ->
      // INACTIVE -> OFFLINE -> DROPPED steps). If so, go through decommission steps to make sure peer replicas are
      // caught up with local replica and we update DataNodeConfig in Helix.
      if (store.recoverFromDecommission() || (clusterMap.isDataNodeInFullAutoMode(replica.getDataNodeId())
          && store.getPreviousState() == ReplicaState.OFFLINE)) {
        try {
          resumeDecommission(partitionName);
        } catch (Exception e) {
          logger.error("Exception occurs when resuming decommission on replica {} with error msg: {}", replica,
              e.getMessage());
          metrics.resumeDecommissionErrorCount.inc();
          throw new StateTransitionException(
              "Exception occurred when resuming decommission on replica " + partitionName, ReplicaOperationFailure);
        }
      }

      // 2. Shut down the store
      if (!shutdownBlobStore(replica.getPartitionId())) {
        throw new StateTransitionException("Failed to shutdown store " + partitionName, ReplicaOperationFailure);
      }
      logger.info("Store {} is successfully shut down during Offline-To-Dropped transition", partitionName);

      // 3. Remove replica from data node configs
      if (primaryClusterParticipant != null) {
        try {
          if (!primaryClusterParticipant.updateDataNodeInfoInCluster(replica, false)) {
            logger.error("Failed to remove partition {} from DataNodeConfig of current node", partitionName);
            throw new StateTransitionException("Failed to remove partition " + partitionName + " from DataNodeConfig",
                StateTransitionException.TransitionErrorCode.HelixUpdateFailure);
          }
          logger.info("Partition {} is successfully removed from DataNodeConfig of current node", partitionName);
        } catch (IllegalStateException e) {
          throw new StateTransitionException(e.getMessage(),
              StateTransitionException.TransitionErrorCode.HelixUpdateFailure);
        }
      }

      // 4. invoke PartitionStateChangeListener in Replication Manager and Stats Manager to remove replica
      logger.info("Invoking state listeners to remove replica {} from stats and replication manager", partitionName);
      if (statsManagerListener != null) {
        statsManagerListener.onPartitionBecomeDroppedFromOffline(partitionName);
      }
      if (replicationManagerListener != null) {
        replicationManagerListener.onPartitionBecomeDroppedFromOffline(partitionName);
      }

      // 5. remove store and delete all files associated with given replica in Storage Manager
      try {
        if (!removeBlobStore(replica.getPartitionId())) {
          throw new StateTransitionException("Failed to remove store " + partitionName + " from storage manager",
              ReplicaOperationFailure);
        }
      } catch (IOException | StoreException e) {
        throw new StateTransitionException("Failed to delete directory for store " + partitionName,
            ReplicaOperationFailure);
      }
      partitionNameToReplicaId.remove(partitionName);
      logger.info("Partition {} is successfully dropped on current node", partitionName);
    }

    /**
     * This method is called by Offline-To-Dropped transition. Any errors/exceptions will be thrown and converted to
     * {@link StateTransitionException}. The error/exception is also recorded in certain metric for alerting purpose.
     * NOTE: there are 4 steps to resume decommission(see comments in method) and the steps should be performed in order.
     * This method basically repeats the Standby-To-Inactive and Inactive-To-Offline transitions. That's why we see
     * replication manager listener is called twice for different transitions.
     */
    private void resumeDecommission(String partitionName) throws Exception {
      logger.info("Resuming decommission on replica {}", partitionName);
      // 1. perform Standby-To-Inactive transition in StorageManager. This is to disable compaction at the very beginning
      //    to avoid position of last PUT in store changes.
      onPartitionBecomeInactiveFromStandby(partitionName);
      if (replicationManagerListener != null && replicaSyncUpManager != null) {
        // 2. perform Standby-To-Inactive transition in ReplicationManager. This is to initiate deactivation on given
        //    partition and will be blocked until peer replicas have caught up with last PUT in corresponding store.
        replicationManagerListener.onPartitionBecomeInactiveFromStandby(partitionName);
        replicaSyncUpManager.waitDeactivationCompleted(partitionName);
        // 3. perform Inactive-To-Offline transition in ReplicationManager. This is to initiate disconnection on given
        //    partition and will be blocked until peer replicas have caught up with last record(i.e DELETE etc) in store.
        replicationManagerListener.onPartitionBecomeOfflineFromInactive(partitionName);
        replicaSyncUpManager.waitDisconnectionCompleted(partitionName);
      }
      // 4. perform Inactive-To-Offline transition in StorageManager. However, we don't do anything currently during
      // this transition
      onPartitionBecomeOfflineFromInactive(partitionName);
      logger.info("Decommission on replica {} is almost done, dropping it from current node", partitionName);
    }
  }
}
