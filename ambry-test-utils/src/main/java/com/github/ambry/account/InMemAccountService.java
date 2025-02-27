/*
 * Copyright 2017 LinkedIn Corp. All rights reserved.
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

package com.github.ambry.account;

import com.github.ambry.frontend.Page;
import com.github.ambry.protocol.DatasetVersionState;
import com.github.ambry.quota.QuotaResourceType;
import com.github.ambry.utils.Pair;
import com.github.ambry.utils.TestUtils;
import com.github.ambry.utils.Utils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;


/**
 * Implementation of {@link AccountService} for test. This implementation synchronizes on all methods.
 */
public class InMemAccountService implements AccountService {
  /**
   * An account defined specifically for the blobs put without specifying target account and container. In the
   * pre-containerization world, a put-blob request does not carry any information which account/container to store
   * the blob. These blobs are assigned to this account if their service ID does not match a valid account, because the
   * target account information is unknown.
   */
  public static final Account UNKNOWN_ACCOUNT =
      new Account(Account.UNKNOWN_ACCOUNT_ID, Account.UNKNOWN_ACCOUNT_NAME, Account.AccountStatus.ACTIVE,
          Account.ACL_INHERITED_BY_CONTAINER_DEFAULT_VALUE, Account.SNAPSHOT_VERSION_DEFAULT_VALUE,
          Arrays.asList(Container.UNKNOWN_CONTAINER, Container.DEFAULT_PUBLIC_CONTAINER,
              Container.DEFAULT_PRIVATE_CONTAINER), Account.QUOTA_RESOURCE_TYPE_DEFAULT_VALUE);
  static final String INMEM_ACCOUNT_UPDATER_PREFIX = "in-memory-account-updater";
  private static final int PAGE_SIZE = 1;
  private final boolean shouldReturnOnlyUnknown;
  private final boolean notifyConsumers;
  private final Map<Short, Account> idToAccountMap = new HashMap<>();
  private final Map<String, Account> nameToAccountMap = new HashMap<>();
  private final Set<Consumer<Collection<Account>>> accountUpdateConsumers = new HashSet<>();
  private final ScheduledExecutorService scheduler;
  private boolean shouldUpdateSucceed = true;
  private final Map<Pair<String, String>, Map<String, Dataset>> nameToDatasetMap = new HashMap<>();
  private final Map<Pair<Short, Short>, Map<Pair<String, String>, DatasetVersionRecord>> idToDatasetVersionMap = new HashMap<>();

  /**
   * Constructor.
   * @param shouldReturnOnlyUnknown {@code true} if always returns {@link InMemAccountService#UNKNOWN_ACCOUNT} when queried
   *                                                     by account name; {@code false} to do the actual query.
   * @param notifyConsumers {@code true} if consumers should be notified of account changes. {@code false} otherwise.
   */
  public InMemAccountService(boolean shouldReturnOnlyUnknown, boolean notifyConsumers) {
    this.shouldReturnOnlyUnknown = shouldReturnOnlyUnknown;
    this.notifyConsumers = notifyConsumers;
    this.scheduler = Utils.newScheduler(1, INMEM_ACCOUNT_UPDATER_PREFIX, false);
  }

  @Override
  public synchronized Account getAccountById(short accountId) {
    return shouldReturnOnlyUnknown ? UNKNOWN_ACCOUNT : idToAccountMap.get(accountId);
  }

  @Override
  public synchronized Account getAccountByName(String accountName) {
    return shouldReturnOnlyUnknown ? UNKNOWN_ACCOUNT : nameToAccountMap.get(accountName);
  }

  @Override
  public synchronized void updateAccounts(Collection<Account> accounts) throws AccountServiceException {
    if (!shouldUpdateSucceed) {
      throw new AccountServiceException("Update failed", AccountServiceErrorCode.InternalError);
    }
    for (Account account : accounts) {
      idToAccountMap.put(account.getId(), account);
      nameToAccountMap.put(account.getName(), account);
    }
    if (notifyConsumers) {
      for (Consumer<Collection<Account>> accountUpdateConsumer : accountUpdateConsumers) {
        accountUpdateConsumer.accept(accounts);
      }
    }
  }

  @Override
  public Collection<Container> updateContainers(String accountName, Collection<Container> containers)
      throws AccountServiceException {
    // input validation
    if (accountName == null || accountName.isEmpty() || containers == null || containers.isEmpty()) {
      throw new AccountServiceException("Account or container is null or empty", AccountServiceErrorCode.BadRequest);
    }
    Account account = getAccountByName(accountName);
    if (account == null) {
      throw new AccountServiceException("Account " + accountName + " is not found", AccountServiceErrorCode.NotFound);
    }

    List<Container> createdContainers = new ArrayList<>();
    short nextContainerId = account.getAllContainers()
        .stream()
        .map(Container::getId)
        .max(Short::compareTo)
        .map(maxId -> (short) (maxId + 1))
        .orElse((short) 1);
    // construct containers based on input container and next containerId
    for (Container container : containers) {
      createdContainers.add(
          new ContainerBuilder(container).setId(nextContainerId).setParentAccountId(account.getId()).build());
      ++nextContainerId;
    }
    account.updateContainerMap(createdContainers);
    updateAccounts(Collections.singletonList(account));
    return createdContainers;
  }

  @Override
  public synchronized DatasetVersionRecord addDatasetVersion(String accountName, String containerName,
      String datasetName, String version, long timeToLiveInSeconds, long creationTimeInMs,
      boolean datasetVersionTtlEnabled, DatasetVersionState datasetVersionState) throws AccountServiceException {
    Account account = nameToAccountMap.get(accountName);
    short accountId = account.getId();
    short containerId = account.getContainerByName(containerName).getId();
    idToDatasetVersionMap.putIfAbsent(new Pair<>(accountId, containerId), new HashMap<>());
    Dataset dataset = getDataset(accountName, containerName, datasetName);
    long updatedExpirationTimeMs = Utils.addSecondsToEpochTime(creationTimeInMs, dataset.getRetentionTimeInSeconds());
    if (datasetVersionTtlEnabled) {
      updatedExpirationTimeMs = Utils.addSecondsToEpochTime(creationTimeInMs, timeToLiveInSeconds);
    }
    if ("LATEST".equals(version)) {
      List<Map.Entry<Pair<String, String>, DatasetVersionRecord>> datasetToDatasetVersionList =
          new ArrayList<>(idToDatasetVersionMap.get(new Pair<>(accountId, containerId)).entrySet());
      datasetToDatasetVersionList.sort(
          (entry1, entry2) -> entry2.getValue().getVersion().compareTo(entry1.getValue().getVersion()));
      if (datasetToDatasetVersionList.size() == 0) {
        version = "1";
      } else {
        version = String.valueOf(Integer.parseInt(datasetToDatasetVersionList.get(0).getValue().getVersion()) + 1);
      }
    }
    DatasetVersionRecord datasetVersionRecord =
        new DatasetVersionRecord(accountId, containerId, datasetName, version, updatedExpirationTimeMs);
    idToDatasetVersionMap.get(new Pair<>(accountId, containerId))
        .put(new Pair<>(datasetName, version), datasetVersionRecord);
    return datasetVersionRecord;
  }

  @Override
  public synchronized DatasetVersionRecord getDatasetVersion(String accountName, String containerName,
      String datasetName, String version) throws AccountServiceException {
    Account account = nameToAccountMap.get(accountName);
    short accountId = account.getId();
    short containerId = account.getContainerByName(containerName).getId();
    DatasetVersionRecord datasetVersionRecord =
        idToDatasetVersionMap.get(new Pair<>(accountId, containerId)).get(new Pair<>(datasetName, version));
    if (datasetVersionRecord == null) {
      throw new AccountServiceException("Dataset version has been deleted", AccountServiceErrorCode.Deleted);
    }
    return datasetVersionRecord;
  }

  @Override
  public synchronized void deleteDatasetVersion(String accountName, String containerName,
      String datasetName, String version) throws AccountServiceException {
    Account account = nameToAccountMap.get(accountName);
    short accountId = account.getId();
    short containerId = account.getContainerByName(containerName).getId();
    idToDatasetVersionMap.get(new Pair<>(accountId, containerId)).remove(new Pair<>(datasetName, version));
  }

  @Override
  public synchronized List<DatasetVersionRecord> getAllValidVersionsOutOfRetentionCount(String accountName,
      String containerName, String datasetName) throws AccountServiceException {
    List<DatasetVersionRecord> datasetVersionRecordList = new ArrayList<>();
    Account account = nameToAccountMap.get(accountName);
    short accountId = account.getId();
    short containerId = account.getContainerByName(containerName).getId();
    Dataset dataset = getDataset(accountName, containerName, datasetName);
    Integer retentionCount = dataset.getRetentionCount();
    if (retentionCount != null && idToDatasetVersionMap.get(new Pair<>(accountId, containerId)) != null) {
      List<Map.Entry<Pair<String, String>, DatasetVersionRecord>> datasetToDatasetVersionList =
          new ArrayList<>(idToDatasetVersionMap.get(new Pair<>(accountId, containerId)).entrySet());
      datasetToDatasetVersionList.sort(
          (entry1, entry2) -> entry2.getValue().getVersion().compareTo(entry1.getValue().getVersion()));
      for (Map.Entry<Pair<String, String>, DatasetVersionRecord> entry : datasetToDatasetVersionList) {
        retentionCount--;
        if (retentionCount >= 0) {
          continue;
        }
        Pair<String, String> key = entry.getKey();
        if (key.getFirst().equals(datasetName)) {
          datasetVersionRecordList.add(entry.getValue());
        }
      }
    }
    return datasetVersionRecordList;
  }

  @Override
  public synchronized boolean addAccountUpdateConsumer(Consumer<Collection<Account>> accountUpdateConsumer) {
    Objects.requireNonNull(accountUpdateConsumer, "accountUpdateConsumer to subscribe cannot be null");
    return accountUpdateConsumers.add(accountUpdateConsumer);
  }

  @Override
  public synchronized boolean removeAccountUpdateConsumer(Consumer<Collection<Account>> accountUpdateConsumer) {
    Objects.requireNonNull(accountUpdateConsumer, "accountUpdateConsumer to unsubscribe cannot be null");
    return accountUpdateConsumers.remove(accountUpdateConsumer);
  }

  @Override
  public synchronized Collection<Account> getAllAccounts() {
    return idToAccountMap.values();
  }

  @Override
  public synchronized void addDataset(Dataset dataset) {
    String accountName = dataset.getAccountName();
    String containerName = dataset.getContainerName();
    nameToDatasetMap.putIfAbsent(new Pair<>(accountName, containerName), new HashMap<>());
    nameToDatasetMap.get(new Pair<>(accountName, containerName)).put(dataset.getDatasetName(), dataset);
  }

  @Override
  public synchronized void updateDataset(Dataset dataset) {
    String accountName = dataset.getAccountName();
    String containerName = dataset.getContainerName();
    nameToDatasetMap.get(new Pair<>(accountName, containerName)).put(dataset.getDatasetName(), dataset);
  }

  @Override
  public synchronized Dataset getDataset(String accountName, String containerName, String datasetName)
      throws AccountServiceException {
    Dataset dataset = nameToDatasetMap.get(new Pair<>(accountName, containerName)).get(datasetName);
    if (dataset == null) {
      throw new AccountServiceException("Dataset has been deleted", AccountServiceErrorCode.Deleted);
    }
    return dataset;
  }

  @Override
  public synchronized Page<String> listAllValidDatasets(String accountName, String containerName, String pageToken) {
    List<String> datasets = new ArrayList<>(nameToDatasetMap.get(new Pair<>(accountName, containerName)).keySet());
    Collections.sort(datasets);
    int index = 0;
    if (pageToken != null) {
      index = Collections.binarySearch(datasets, pageToken);
    }
    return new Page<>(datasets.subList(index, index + PAGE_SIZE), datasets.get(index + PAGE_SIZE));
  }

  @Override
  public synchronized void deleteDataset(String accountName, String containerName, String datasetName) {
    Account account = nameToAccountMap.get(accountName);
    short accountId = account.getId();
    short containerId = account.getContainerByName(containerName).getId();
    Map<Pair<String, String>, DatasetVersionRecord> datasetToDatasetVersionMap =
        idToDatasetVersionMap.get(new Pair<>(accountId, containerId));
    if (datasetToDatasetVersionMap != null) {
      for (Map.Entry<Pair<String, String>, DatasetVersionRecord> entry : datasetToDatasetVersionMap.entrySet()) {
        Pair<String, String> key = entry.getKey();
        if (key.getFirst().equals(datasetName)) {
          idToDatasetVersionMap.remove(key);
        }
      }
    }
    nameToDatasetMap.get(new Pair<>(accountName, containerName)).remove(datasetName);
  }

  @Override
  public synchronized List<DatasetVersionRecord> getAllValidVersion(String accountName, String containerName,
      String datasetName) {
    List<DatasetVersionRecord> datasetVersionRecords = new ArrayList<>();
    Account account = nameToAccountMap.get(accountName);
    short accountId = account.getId();
    short containerId = account.getContainerByName(containerName).getId();
    Map<Pair<String, String>, DatasetVersionRecord> datasetToDatasetVersionMap =
        idToDatasetVersionMap.get(new Pair<>(accountId, containerId));
    if (datasetToDatasetVersionMap != null) {
      for (Map.Entry<Pair<String, String>, DatasetVersionRecord> entry : datasetToDatasetVersionMap.entrySet()) {
        Pair<String, String> key = entry.getKey();
        DatasetVersionRecord datasetVersionRecord = entry.getValue();
        if (key.getFirst().equals(datasetName)
            && Utils.compareTimes(datasetVersionRecord.getExpirationTimeMs(), System.currentTimeMillis()) > 0) {
          datasetVersionRecords.add(datasetVersionRecord);
        }
      }
    }
    return datasetVersionRecords;
  }

  @Override
  public synchronized void updateDatasetVersionState (String accountName, String containerName, String datasetName,
      String version, DatasetVersionState datasetVersionState) {
    //no-op
  }

  @Override
  public void close() {
    // no op
  }

  /**
   * Clears all the accounts in this {@code AccountService}.
   */
  public synchronized void clear() {
    Collection<Account> allAccounts = idToAccountMap.values();
    idToAccountMap.clear();
    nameToAccountMap.clear();
    if (notifyConsumers) {
      for (Consumer<Collection<Account>> accountUpdateConsumer : accountUpdateConsumers) {
        accountUpdateConsumer.accept(allAccounts);
      }
    }
  }

  /**
   * Creates and adds an {@link Account} to this {@link AccountService}. The account will contain one container
   * with {@link Container#DEFAULT_PUBLIC_CONTAINER_ID}, one with {@link Container#DEFAULT_PRIVATE_CONTAINER_ID} and
   * one other random {@link Container}.
   * @return the {@link Account} that was created and added.
   */
  public synchronized Account createAndAddRandomAccount() {
    return createAndAddRandomAccount(
        QuotaResourceType.values()[Utils.getRandomShort(TestUtils.RANDOM) % QuotaResourceType.values().length]);
  }

  /**
   * Creates and adds an {@link Account} to this {@link AccountService}. The account will contain one container
   * with {@link Container#DEFAULT_PUBLIC_CONTAINER_ID}, one with {@link Container#DEFAULT_PRIVATE_CONTAINER_ID} and
   * one other random {@link Container}.
   * @param quotaResourceType {@link QuotaResourceType} for the account.
   * @return the {@link Account} that was created and added.
   */
  public synchronized Account createAndAddRandomAccount(QuotaResourceType quotaResourceType) {
    Account account = generateRandomAccount(quotaResourceType);
    try {
      updateAccounts(Collections.singletonList(account));
    } catch (AccountServiceException ase) {
      throw new IllegalStateException(ase);
    }
    return account;
  }

  /**
   * Generates an {@link Account} but does not add it to this {@link AccountService}. The account will contain one
   * container with {@link Container#DEFAULT_PUBLIC_CONTAINER_ID}, one with
   * {@link Container#DEFAULT_PRIVATE_CONTAINER_ID} and one other random {@link Container}.
   * @return the {@link Account} that was created.
   */
  public synchronized Account generateRandomAccount() {
    return generateRandomAccount(
        QuotaResourceType.values()[Utils.getRandomShort(TestUtils.RANDOM) % QuotaResourceType.values().length]);
  }

  /**
   * Generates an {@link Account} but does not add it to this {@link AccountService}. The account will contain one
   * container with {@link Container#DEFAULT_PUBLIC_CONTAINER_ID}, one with
   * {@link Container#DEFAULT_PRIVATE_CONTAINER_ID} and one other random {@link Container}.
   * @param quotaResourceType {@link QuotaResourceType} for the account.
   * @return the {@link Account} that was created.
   */
  public synchronized Account generateRandomAccount(QuotaResourceType quotaResourceType) {
    short refAccountId;
    String refAccountName;
    do {
      refAccountId = Utils.getRandomShort(TestUtils.RANDOM);
      refAccountName = TestUtils.getRandomString(10);
    } while (idToAccountMap.containsKey(refAccountId) || nameToAccountMap.containsKey(refAccountName));
    Account.AccountStatus refAccountStatus = Account.AccountStatus.ACTIVE;
    Container randomContainer = getRandomContainer(refAccountId);
    Container publicContainer =
        new ContainerBuilder(Container.DEFAULT_PUBLIC_CONTAINER).setParentAccountId(refAccountId)
            .setNamedBlobMode(Container.NamedBlobMode.OPTIONAL)
            .build();
    Container privateContainer =
        new ContainerBuilder(Container.DEFAULT_PRIVATE_CONTAINER).setParentAccountId(refAccountId)
            .setNamedBlobMode(Container.NamedBlobMode.OPTIONAL)
            .build();
    return new AccountBuilder(refAccountId, refAccountName, refAccountStatus, quotaResourceType).addOrUpdateContainer(
        publicContainer).addOrUpdateContainer(privateContainer).addOrUpdateContainer(randomContainer).build();
  }

  /**
   * @param shouldUpdateSucceed the new value of {@code shouldUpdateSucceed}, which will be returned by subsequent
   *                            {@link #updateAccounts(Collection)} calls.
   */
  public void setShouldUpdateSucceed(boolean shouldUpdateSucceed) {
    this.shouldUpdateSucceed = shouldUpdateSucceed;
  }

  /**
   * Adds {@code replicationPolicy} to {@code container}.
   * @param container the {@link Container} to add the replication policy to.
   * @param replicationPolicy the replication policy to add to {@code container}.
   * @return the new container object with the appropriate replication policy if the operation suceeded. {@code null}
   * otherwise
   */
  public Container addReplicationPolicyToContainer(Container container, String replicationPolicy)
      throws AccountServiceException {
    Container newContainer = new ContainerBuilder(container).setReplicationPolicy(replicationPolicy).build();
    Account account = getAccountById(container.getParentAccountId());
    Account newAccount = new AccountBuilder(account).addOrUpdateContainer(newContainer).build();
    updateAccounts(Collections.singleton(newAccount));
    return newContainer;
  }

  /**
   * Creates and returns a random {@link Container} for {@code accountId}.
   * @param accountId the account id for the container
   * @return returns a random {@link Container} for {@code accountId}
   */
  public Container getRandomContainer(short accountId) {
    // adding +2 so that the ID is not 0 or 1
    short refContainerId = (short) (TestUtils.RANDOM.nextInt(Short.MAX_VALUE - 1) + 2);
    String refContainerName = TestUtils.getRandomString(10);
    Container.ContainerStatus refContainerStatus = Container.ContainerStatus.ACTIVE;
    String refContainerDescription = TestUtils.getRandomString(10);
    boolean refContainerEncryption = TestUtils.RANDOM.nextBoolean();
    boolean refContainerPreviousEncryption = refContainerEncryption || TestUtils.RANDOM.nextBoolean();
    boolean refContainerCaching = true;
    boolean refContainerMediaScanDisabled = TestUtils.RANDOM.nextBoolean();
    boolean refContainerBackupEnabled = TestUtils.RANDOM.nextBoolean();
    String refUserMetadataKeyToNotPrefix = TestUtils.getRandomString(10);
    long refCacheTtlInSecond = TestUtils.RANDOM.nextInt(123456) + 123456;
    return new ContainerBuilder(refContainerId, refContainerName, refContainerStatus, refContainerDescription,
        accountId).setEncrypted(refContainerEncryption)
        .setPreviouslyEncrypted(refContainerPreviousEncryption)
        .setCacheable(refContainerCaching)
        .setMediaScanDisabled(refContainerMediaScanDisabled)
        .setTtlRequired(false)
        .setSecurePathRequired(false)
        .setBackupEnabled(refContainerBackupEnabled)
        .setNamedBlobMode(Container.NamedBlobMode.OPTIONAL)
        .setUserMetadataKeysToNotPrefixInResponse(Collections.singleton(refUserMetadataKeyToNotPrefix))
        .setCacheTtlInSecond(refCacheTtlInSecond)
        .build();
  }
}
