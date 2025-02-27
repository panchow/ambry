/*
 * Copyright 2023 LinkedIn Corp. All rights reserved.
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

import com.github.ambry.account.mysql.MySqlAccountStore;
import com.github.ambry.commons.Notifier;
import com.github.ambry.config.MySqlAccountServiceConfig;
import com.github.ambry.frontend.Page;
import com.github.ambry.protocol.DatasetVersionState;
import com.github.ambry.utils.Pair;
import com.github.ambry.utils.SystemTime;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.ambry.account.AccountUtils.*;
import static com.github.ambry.account.MySqlAccountService.*;


public class CachedAccountService extends AbstractAccountService {
  private static final Logger logger = LoggerFactory.getLogger(CachedAccountService.class);
  public static final String SEPARATOR = ":";
  // Cache parameters (initial capacity, load factor and max limit) for recentNotFoundContainersCache
  private static final int cacheInitialCapacity = 100;
  private static final float cacheLoadFactor = 0.75f;
  private static final int cacheMaxLimit = 1000;

  private final BackupFileManager backupFileManager;
  private final Set<String> recentNotFoundContainersCache;
  private final AccountServiceMetrics accountServiceMetrics;
  private final ReadWriteLock infoMapLock;
  private final MySqlAccountServiceConfig config;
  private final boolean writeCacheAfterUpdate;
  private final AtomicBoolean open = new AtomicBoolean(true);
  private final ScheduledExecutorService scheduler;
  private final Supplier<MySqlAccountStore> supplier;
  private volatile MySqlAccountStore mySqlAccountStore;
  private boolean needRefresh = false;
  private long lastSyncTime = -1;
  private boolean disableGenerateColumn;

  /**
   * The service used for mysql migration which includes all common logic.
   */
  public CachedAccountService(MySqlAccountStore mySqlAccountStore, Supplier<MySqlAccountStore> supplier,
      AccountServiceMetrics accountServiceMetrics, MySqlAccountServiceConfig config, Notifier<String> notifier,
      String backupDir, ScheduledExecutorService scheduler, boolean disableGenerateColumn) throws IOException {
    super(config, Objects.requireNonNull(accountServiceMetrics, "accountServiceMetrics cannot be null"), notifier);
    this.mySqlAccountStore = mySqlAccountStore;
    this.accountServiceMetrics = accountServiceMetrics;
    this.config = config;
    this.writeCacheAfterUpdate = config.writeCacheAfterUpdate;
    this.recentNotFoundContainersCache = Collections.newSetFromMap(
        Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(cacheInitialCapacity, cacheLoadFactor, true) {
          protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > cacheMaxLimit;
          }
        }));
    this.infoMapLock = new ReentrantReadWriteLock();
    // create backup file manager for persisting and retrieving Account metadata on local disk
    this.backupFileManager = new BackupFileManager(accountServiceMetrics, backupDir, config.maxBackupFileCount);
    this.scheduler = scheduler;
    this.supplier = supplier;
    this.disableGenerateColumn = disableGenerateColumn;
    accountServiceMetrics.trackTimeSinceLastSync(this::getTimeInSecondsSinceLastSync);
    accountServiceMetrics.trackContainerCount(this::getContainerCount);
    // Initialize cache from backup file on disk
    initCacheFromBackupFile();
    // Fetches added or modified accounts and containers from mysql db and schedules to execute it periodically
    initialFetchAndSchedule();
  }

  @Override
  protected void checkOpen() {
    if (!open.get()) {
      throw new IllegalStateException("AccountService is closed.");
    }
  }

  @Override
  protected void onAccountChangeMessage(String topic, String message) {
    if (!open.get()) {
      // take no action instead of throwing an exception to silence noisy log messages when a message is received while
      // closing the AccountService.
      return;
    }
    try {
      switch (message) {
        case FULL_ACCOUNT_METADATA_CHANGE_MESSAGE:
          logger.info("Processing message={} for topic={}", message, topic);
          fetchAndUpdateCache();
          logger.trace("Completed processing message={} for topic={}", message, topic);
          break;
        default:
          accountServiceMetrics.unrecognizedMessageErrorCount.inc();
          throw new RuntimeException("Could not understand message=" + message + " for topic=" + topic);
      }
    } catch (Exception e) {
      logger.error("Exception occurred when processing message={} for topic={}.", message, topic, e);
      accountServiceMetrics.remoteDataCorruptionErrorCount.inc();
      accountServiceMetrics.fetchRemoteAccountErrorCount.inc();
    }
  }

  /**
   * Fetches added or modified accounts and containers from mysql database and schedules to execute periodically.
   */
  public void initialFetchAndSchedule() {
    fetchAndUpdateCacheNoThrow();
    if (scheduler != null) {
      scheduler.scheduleAtFixedRate(this::fetchAndUpdateCacheNoThrow, config.updaterPollingIntervalSeconds,
          config.updaterPollingIntervalSeconds, TimeUnit.SECONDS);
      logger.info("Background account updater will fetch accounts from mysql db at intervals of {} seconds",
          config.updaterPollingIntervalSeconds);
    }
    maybeSubscribeChangeTopic(false);
  }

  /**
   * Initializes in-memory {@link AccountInfoMap} with accounts and containers stored in backup copy on local disk.
   */
  public void initCacheFromBackupFile() {
    long aMonthAgo = SystemTime.getInstance().seconds() - TimeUnit.DAYS.toSeconds(30);
    Collection<Account> accounts = backupFileManager.getLatestAccounts(aMonthAgo);
    AccountInfoMap accountInfoMap = null;
    if (accounts != null) {
      try {
        accountInfoMap = new AccountInfoMap(accounts);
      } catch (Exception e) {
        logger.warn("Failure in parsing of Account Metadata from local backup file", e);
      }
    }
    if (accountInfoMap == null) {
      accountInfoMap = new AccountInfoMap(accountServiceMetrics);
    }
    // Refresh last modified time of Accounts and Containers in cache
    accountInfoMap.refreshLastModifiedTime();
    accountInfoMapRef.set(accountInfoMap);
  }

  /**
   * Fetches all the accounts and containers that have been created or modified in the mysql database since the
   * last modified/sync time and loads into in-memory {@link AccountInfoMap}.
   */
  private void fetchAndUpdateCacheNoThrow() {
    try {
      fetchAndUpdateCache();
    } catch (Throwable e) {
      logger.error("fetchAndUpdateCache failed", e);
    }
  }

  /**
   * Fetches all the accounts and containers that have been created or modified in the mysql database since the
   * last modified/sync time and loads into in-memory {@link AccountInfoMap}.
   */
  protected synchronized void fetchAndUpdateCache() throws SQLException {
    if (mySqlAccountStore == null) {
      mySqlAccountStore = this.supplier.get();
    }
    try {
      // Find last modified time of Accounts and containers in cache.
      long lastModifiedTime = accountInfoMapRef.get().getLastModifiedTime();
      logger.info("Syncing from database using lastModifiedTime = {}", new Date(lastModifiedTime));

      long startTimeMs = System.currentTimeMillis();
      // Fetch added/modified accounts and containers from MySql database since LMT
      Collection<Account> updatedAccountsInDB = mySqlAccountStore.getNewAccounts(lastModifiedTime);
      Collection<Container> updatedContainersInDB = mySqlAccountStore.getNewContainers(lastModifiedTime);
      long endTimeMs = System.currentTimeMillis();
      accountServiceMetrics.fetchRemoteAccountTimeInMs.update(endTimeMs - startTimeMs);
      // Close connection and get fresh one next time
      mySqlAccountStore.closeConnection();

      if (!updatedAccountsInDB.isEmpty() || !updatedContainersInDB.isEmpty()) {
        logger.info("Found {} accounts and {} containers", updatedAccountsInDB.size(), updatedContainersInDB.size());
        for (Account account : updatedAccountsInDB) {
          logger.info("Found account {}", account.getName());
        }
        for (Container container : updatedContainersInDB) {
          logger.info("Found container {}", container.getName());
        }

        // Update cache with fetched accounts and containers
        updateAccountsInCache(updatedAccountsInDB);
        updateContainersInCache(updatedContainersInDB);

        // Refresh last modified time of Accounts and Containers in cache
        accountInfoMapRef.get().refreshLastModifiedTime();

        // At this point we can safely say cache is refreshed
        needRefresh = false;
        lastSyncTime = endTimeMs;

        // Notify updated accounts to consumers
        Set<Account> updatedAccounts = new HashSet<>();
        updatedAccountsInDB.forEach(
            account -> updatedAccounts.add(accountInfoMapRef.get().getAccountById(account.getId())));
        updatedContainersInDB.forEach(
            container -> updatedAccounts.add(accountInfoMapRef.get().getAccountById(container.getParentAccountId())));
        notifyAccountUpdateConsumers(updatedAccounts, false);

        // Persist all account metadata to back up file on disk.
        Collection<Account> accountCollection;
        infoMapLock.readLock().lock();
        try {
          accountCollection = accountInfoMapRef.get().getAccounts();
        } finally {
          infoMapLock.readLock().unlock();
        }
        backupFileManager.persistAccountMap(accountCollection, backupFileManager.getLatestVersion() + 1,
            SystemTime.getInstance().seconds());
      } else {
        // Cache is up to date
        needRefresh = false;
        lastSyncTime = endTimeMs;
      }
    } catch (SQLException e) {
      accountServiceMetrics.fetchRemoteAccountErrorCount.inc();
      logger.error("Fetching accounts from MySql DB failed: {}", e.getMessage());
      throw e;
    }
  }

  /**
   * Updates {@link Account}s to in-memory cache.
   * @param accounts added or modified {@link Account}s
   */
  private void updateAccountsInCache(Collection<Account> accounts) {
    infoMapLock.writeLock().lock();
    try {
      accountInfoMapRef.get().addOrUpdateAccounts(accounts);
    } finally {
      infoMapLock.writeLock().unlock();
    }
  }

  /**
   * Updates {@link Container}s to in-memory cache.
   * @param containers added or modified {@link Container}s
   */
  private void updateContainersInCache(Collection<Container> containers) {
    infoMapLock.writeLock().lock();
    try {
      accountInfoMapRef.get().addOrUpdateContainers(containers);
    } finally {
      infoMapLock.writeLock().unlock();
    }

    // Remove containers from not-found LRU cache.
    for (Container container : containers) {
      recentNotFoundContainersCache.remove(
          getAccountById(container.getParentAccountId()).getName() + SEPARATOR + container.getName());
    }
  }

  /**
   * Gets an {@link Account} by its id.
   * @param accountId The id of an {@link Account} to get.
   * @return The {@link Account} with the specified id. {@code null} if such an {@link Account} does not exist.
   */
  public Account getAccountById(short accountId) {
    infoMapLock.readLock().lock();
    try {
      return accountInfoMapRef.get().getAccountById(accountId);
    } finally {
      infoMapLock.readLock().unlock();
    }
  }

  /**
   * Gets an {@link Account} by its name.
   * @param accountName The name of an {@link Account} to get. Cannot be {@code null}.
   * @return The {@link Account} with the specified name. {@code null} if such an {@link Account} does not exist.
   */
  public Account getAccountByName(String accountName) {
    infoMapLock.readLock().lock();
    try {
      return accountInfoMapRef.get().getAccountByName(accountName);
    } finally {
      infoMapLock.readLock().unlock();
    }
  }

  /**
   * Get an existing container from a given account.
   * @param accountName the name of account which container belongs to.
   * @param containerName the name of container to get.
   * @return the requested {@link Container} object or null if not present.
   * @throws AccountServiceException if exception occurs when getting container.
   */
  public Container getContainerByNameHelper(String accountName, String containerName) throws AccountServiceException {

    if (recentNotFoundContainersCache.contains(accountName + SEPARATOR + containerName)) {
      // If container was not found in recent get attempts, avoid another query to db and return null.
      return null;
    }

    Account account = getAccountByName(accountName);
    if (account == null) {
      return null;
    }
    Container container = account.getContainerByName(containerName);
    if (container == null) {
      // If container is not present in the cache, query from mysql db
      try {
        container = mySqlAccountStore.getContainerByName(account.getId(), containerName);
        if (container != null) {
          // write container to in-memory cache
          updateContainersInCache(Collections.singletonList(container));
          logger.info("Container {} in Account {} is not found locally; Fetched from mysql db", containerName,
              accountName);
          accountServiceMetrics.onDemandContainerFetchCount.inc();
        } else {
          // Add account_container to not-found LRU cache
          recentNotFoundContainersCache.add(accountName + SEPARATOR + containerName);
          logger.error("Container {} is not found in Account {}", containerName, accountName);
        }
      } catch (SQLException e) {
        throw translateSQLException(e);
      }
    }
    return container;
  }

  /**
   * Get an existing container from a given account.
   * @param accountId the name of account which container belongs to.
   * @param containerId the id of container to get.
   * @return the requested {@link Container} object or null if not present.
   * @throws AccountServiceException if exception occurs when getting container.
   */
  public Container getContainerByIdHelper(short accountId, Short containerId) throws AccountServiceException {
    Account account = getAccountById(accountId);
    if (account == null) {
      return null;
    }
    Container container = account.getContainerById(containerId);
    if (container == null) {
      // If container is not present in the cache, query from mysql db
      try {
        container = mySqlAccountStore.getContainerById(accountId, containerId);
        if (container != null) {
          // write container to in-memory cache
          updateContainersInCache(Collections.singletonList(container));
          logger.info("Container Id {} in Account {} is not found locally, fetched from mysql db", containerId,
              account.getName());
          accountServiceMetrics.onDemandContainerFetchCount.inc();
        } else {
          logger.error("Container Id {} is not found in Account {}", containerId, account.getName());
          // Note: We are not using LRU cache for storing recent unsuccessful get attempts by container Ids since this
          // will be called in getBlob() path which should have valid account-container in most cases.
        }
      } catch (SQLException e) {
        throw translateSQLException(e);
      }
    }
    return container;
  }

  @Override
  public void close() throws IOException {
    maybeUnsubscribeChangeTopic();
    open.set(false);
  }

  @Override
  public void updateAccounts(Collection<Account> accounts) throws AccountServiceException {
    Objects.requireNonNull(accounts, "accounts cannot be null");
    if (accounts.isEmpty()) {
      throw new IllegalArgumentException("Empty account collection to update.");
    }
    if (mySqlAccountStore == null) {
      throw new AccountServiceException("MySql Account store is not accessible", AccountServiceErrorCode.InternalError);
    }

    if (config.updateDisabled) {
      throw new AccountServiceException("Updates have been disabled", AccountServiceErrorCode.UpdateDisabled);
    }

    if (hasDuplicateAccountIdOrName(accounts)) {
      accountServiceMetrics.updateAccountErrorCount.inc();
      throw new AccountServiceException("Duplicate account id or name exist in the accounts to update",
          AccountServiceErrorCode.ResourceConflict);
    }

    // Check for name/id/version conflicts between the accounts and containers being updated with those in local cache.
    // There is a slight chance that we have conflicts local cache, but not with MySql database. This will happen if
    // but the local cache is not yet refreshed with latest account info.
    infoMapLock.readLock().lock();
    try {
      AccountInfoMap accountInfoMap = accountInfoMapRef.get();
      if (accountInfoMap.hasConflictingAccount(accounts, config.ignoreVersionMismatch)) {
        logger.error("Accounts={} conflict with the accounts in local cache. Cancel the update operation.", accounts);
        accountServiceMetrics.updateAccountErrorCount.inc();
        throw new AccountServiceException("Input accounts conflict with the accounts in local cache",
            AccountServiceErrorCode.ResourceConflict);
      }
      for (Account account : accounts) {
        if (accountInfoMap.hasConflictingContainer(account.getAllContainers(), account.getId(),
            config.ignoreVersionMismatch)) {
          logger.error(
              "Containers={} under Account={} conflict with the containers in local cache. Cancel the update operation.",
              account.getAllContainers(), account.getId());
          accountServiceMetrics.updateAccountErrorCount.inc();
          throw new AccountServiceException("Containers in account " + account.getId() + " conflict with local cache",
              AccountServiceErrorCode.ResourceConflict);
        }
      }
    } finally {
      infoMapLock.readLock().unlock();
    }

    // write added/modified accounts to database
    try {
      updateAccountsWithMySqlStore(accounts);
    } catch (SQLException e) {
      logger.error("Failed updating accounts={} in MySql DB", accounts, e);
      handleSQLException(e);
    }
    // Tell the world
    publishChangeNotice();

    if (writeCacheAfterUpdate) {
      // Write accounts to in-memory cache. Snapshot version will be out of date until the next DB refresh.
      updateAccountsInCache(accounts);
    }
  }

  @Override
  public void updateResolvedContainers(Account account, Collection<Container> resolvedContainers)
      throws AccountServiceException {
    try {
      updateContainersWithMySqlStore(account, resolvedContainers);
    } catch (SQLException e) {
      logger.error("Failed updating containers {} in MySql DB", resolvedContainers, e);
      handleSQLException(e);
    }
    // Tell the world
    publishChangeNotice();

    if (writeCacheAfterUpdate) {
      // Write containers to in-memory cache. Snapshot version will be out of date until the next DB refresh.
      updateContainersInCache(resolvedContainers);
    }
  }

  @Override
  public Collection<Container> updateContainers(String accountName, Collection<Container> containers)
      throws AccountServiceException {
    try {
      return super.updateContainers(accountName, containers);
    } catch (AccountServiceException ase) {
      if (needRefresh) {
        // refresh and retry (with regenerated containerIds)
        try {
          fetchAndUpdateCache();
        } catch (SQLException e) {
          throw translateSQLException(e);
        }
        accountServiceMetrics.conflictRetryCount.inc();
        return super.updateContainers(accountName, containers);
      } else {
        throw ase;
      }
    }
  }

  @Override
  protected void publishChangeNotice() {
    // TODO: can optimize this by sending the account/container payload as the topic message,
    // so subscribers can update their cache and avoid the database query.
    super.publishChangeNotice();
  }

  @Override
  public void addDataset(Dataset dataset) throws AccountServiceException {
    try {
      Pair<Short, Short> accountAndContainerIdPair =
          getAccountAndContainerIdFromName(dataset.getAccountName(), dataset.getContainerName());
      if (mySqlAccountStore == null) {
        mySqlAccountStore = this.supplier.get();
      }
      mySqlAccountStore.addDataset(accountAndContainerIdPair.getFirst(), accountAndContainerIdPair.getSecond(),
          dataset);
    } catch (SQLException e) {
      throw translateSQLException(e);
    }
  }

  @Override
  public void updateDataset(Dataset dataset) throws AccountServiceException {
    try {
      Pair<Short, Short> accountAndContainerIdPair =
          getAccountAndContainerIdFromName(dataset.getAccountName(), dataset.getContainerName());
      if (mySqlAccountStore == null) {
        mySqlAccountStore = this.supplier.get();
      }
      mySqlAccountStore.updateDataset(accountAndContainerIdPair.getFirst(), accountAndContainerIdPair.getSecond(),
          dataset);
    } catch (SQLException e) {
      throw translateSQLException(e);
    }
  }

  @Override
  public Dataset getDataset(String accountName, String containerName, String datasetName)
      throws AccountServiceException {
    try {
      Pair<Short, Short> accountAndContainerIdPair = getAccountAndContainerIdFromName(accountName, containerName);
      if (mySqlAccountStore == null) {
        mySqlAccountStore = this.supplier.get();
      }
      return mySqlAccountStore.getDataset(accountAndContainerIdPair.getFirst(), accountAndContainerIdPair.getSecond(),
          accountName, containerName, datasetName);
    } catch (SQLException e) {
      throw translateSQLException(e);
    }
  }

  @Override
  public void deleteDataset(String accountName, String containerName, String datasetName)
      throws AccountServiceException {
    try {
      Pair<Short, Short> accountAndContainerIdPair = getAccountAndContainerIdFromName(accountName, containerName);
      if (mySqlAccountStore == null) {
        mySqlAccountStore = this.supplier.get();
      }
      mySqlAccountStore.deleteDataset(accountAndContainerIdPair.getFirst(), accountAndContainerIdPair.getSecond(),
          datasetName);
    } catch (SQLException e) {
      throw translateSQLException(e);
    }
  }

  @Override
  public DatasetVersionRecord addDatasetVersion(String accountName, String containerName, String datasetName,
      String version, long timeToLiveInSeconds, long creationTimeInMs, boolean datasetVersionTtlEnabled,
      DatasetVersionState datasetVersionState) throws AccountServiceException {
    try {
      Pair<Short, Short> accountAndContainerIdPair = getAccountAndContainerIdFromName(accountName, containerName);
      if (mySqlAccountStore == null) {
        mySqlAccountStore = this.supplier.get();
      }
      return mySqlAccountStore.addDatasetVersion(accountAndContainerIdPair.getFirst(),
          accountAndContainerIdPair.getSecond(), accountName, containerName, datasetName, version, timeToLiveInSeconds,
          creationTimeInMs, datasetVersionTtlEnabled, datasetVersionState);
    } catch (SQLException e) {
      throw translateSQLException(e);
    }
  }

  @Override
  public void updateDatasetVersionState(String accountName, String containerName, String datasetName,
      String version, DatasetVersionState datasetVersionState) throws AccountServiceException {
    try {
      Pair<Short, Short> accountAndContainerIdPair = getAccountAndContainerIdFromName(accountName, containerName);
      if (mySqlAccountStore == null) {
        mySqlAccountStore = this.supplier.get();
      }
      mySqlAccountStore.updateDatasetVersionState(accountAndContainerIdPair.getFirst(),
          accountAndContainerIdPair.getSecond(), accountName, containerName, datasetName, version, datasetVersionState);
    } catch (SQLException e) {
      throw translateSQLException(e);
    }
  }

  @Override
  public DatasetVersionRecord getDatasetVersion(String accountName, String containerName, String datasetName,
      String version) throws AccountServiceException {
    try {
      Pair<Short, Short> accountAndContainerIdPair = getAccountAndContainerIdFromName(accountName, containerName);
      if (mySqlAccountStore == null) {
        mySqlAccountStore = this.supplier.get();
      }
      return mySqlAccountStore.getDatasetVersion(accountAndContainerIdPair.getFirst(),
          accountAndContainerIdPair.getSecond(), accountName, containerName, datasetName, version);
    } catch (SQLException e) {
      throw translateSQLException(e);
    }
  }

  @Override
  public void deleteDatasetVersion(String accountName, String containerName, String datasetName,
      String version) throws AccountServiceException {
    try {
      Pair<Short, Short> accountAndContainerIdPair = getAccountAndContainerIdFromName(accountName, containerName);
      if (mySqlAccountStore == null) {
        mySqlAccountStore = this.supplier.get();
      }
      mySqlAccountStore.deleteDatasetVersion(accountAndContainerIdPair.getFirst(),
          accountAndContainerIdPair.getSecond(), datasetName, version);
    } catch (SQLException e) {
      throw translateSQLException(e);
    }
  }

  @Override
  public List<DatasetVersionRecord> getAllValidVersionsOutOfRetentionCount(String accountName,
      String containerName, String datasetName) throws AccountServiceException {
    try {
      Pair<Short, Short> accountAndContainerIdPair = getAccountAndContainerIdFromName(accountName, containerName);
      if (mySqlAccountStore == null) {
        mySqlAccountStore = this.supplier.get();
      }
      return mySqlAccountStore.getAllValidVersionsOutOfRetentionCount(accountAndContainerIdPair.getFirst(),
          accountAndContainerIdPair.getSecond(), accountName, containerName, datasetName);
    } catch (SQLException e) {
      throw translateSQLException(e);
    }
  }

  @Override
  public List<DatasetVersionRecord> getAllValidVersion(String accountName, String containerName, String datasetName)
      throws AccountServiceException {
    try {
      Pair<Short, Short> accountAndContainerIdPair = getAccountAndContainerIdFromName(accountName, containerName);
      if (mySqlAccountStore == null) {
        mySqlAccountStore = this.supplier.get();
      }
      return mySqlAccountStore.getAllValidVersionForDatasetDeletion(accountAndContainerIdPair.getFirst(),
          accountAndContainerIdPair.getSecond(), datasetName);
    } catch (SQLException e) {
      throw translateSQLException(e);
    }
  }

  @Override
  public Page<String> listAllValidDatasets(String accountName, String containerName, String pageToken) throws AccountServiceException {
    try {
      Pair<Short, Short> accountAndContainerIdPair = getAccountAndContainerIdFromName(accountName, containerName);
      if (mySqlAccountStore == null) {
        mySqlAccountStore = this.supplier.get();
      }
      return mySqlAccountStore.listAllValidDatasets(accountAndContainerIdPair.getFirst(),
          accountAndContainerIdPair.getSecond(), pageToken);
    } catch (SQLException e) {
      throw translateSQLException(e);
    }
  }

  /**
   * Gets all the {@link Account}s in this {@code AccountService}. The {@link Account}s <em>MUST</em> have their
   * ids and names one-to-one mapped.
   * @return A collection of {@link Account}s.
   */
  public Collection<Account> getAllAccountsHelper() {
    infoMapLock.readLock().lock();
    try {
      return accountInfoMapRef.get().getAccounts();
    } finally {
      infoMapLock.readLock().unlock();
    }
  }

  /**
   * @return the time in seconds since the last database sync / cache refresh
   */
  public int getTimeInSecondsSinceLastSync() {
    return lastSyncTime > 0 ? (int) (System.currentTimeMillis() - lastSyncTime) / 1000 : Integer.MAX_VALUE;
  }

  /**
   * @return the total number of containers in all accounts.
   */
  public int getContainerCount() {
    return accountInfoMapRef.get().getContainerCount();
  }

  /**
   * @return set (LRA cache) of containers not found in recent get attempts. Used in only tests.
   */
  public Set<String> getRecentNotFoundContainersCache() {
    return Collections.unmodifiableSet(recentNotFoundContainersCache);
  }

  /**
   * Updates MySql DB with added or modified {@link Account}s
   * @param accounts collection of {@link Account}s
   * @throws SQLException
   */
  protected void updateAccountsWithMySqlStore(Collection<Account> accounts) throws SQLException {
    long startTimeMs = System.currentTimeMillis();
    logger.trace("Start updating accounts={} into MySql DB", accounts);

    // Get account and container changes info
    List<AccountUpdateInfo> accountsUpdateInfo = new ArrayList<>();
    for (Account account : accounts) {

      boolean isAccountAdded = false, isAccountUpdated = false;
      List<Container> addedContainers;
      List<Container> updatedContainers = new ArrayList<>();

      Account accountInCache = getAccountById(account.getId());
      if (accountInCache == null) {
        isAccountAdded = true;
        addedContainers = new ArrayList<>(account.getAllContainers());
      } else {
        if (!accountInCache.equalsWithoutContainers(account)) {
          isAccountUpdated = true;
        }
        // Get list of added and updated containers in the account.
        Pair<List<Container>, List<Container>> addedOrUpdatedContainers =
            getUpdatedContainers(account, account.getAllContainers());
        addedContainers = addedOrUpdatedContainers.getFirst();
        updatedContainers = addedOrUpdatedContainers.getSecond();
      }

      accountsUpdateInfo.add(
          new AccountUpdateInfo(account, isAccountAdded, isAccountUpdated, addedContainers, updatedContainers));
    }

    // Write changes to MySql db.
    mySqlAccountStore.updateAccounts(accountsUpdateInfo, disableGenerateColumn);

    long timeForUpdate = System.currentTimeMillis() - startTimeMs;
    logger.trace("Completed updating accounts={} in MySql DB, took time={} ms", accounts, timeForUpdate);
    accountServiceMetrics.updateAccountTimeInMs.update(timeForUpdate);
  }

  /**
   * Updates MySql DB with added or modified {@link Container}s of a given account
   * @param account parent {@link Account} for the {@link Container}s
   * @param containers collection of {@link Container}s
   * @throws SQLException
   */
  private void updateContainersWithMySqlStore(Account account, Collection<Container> containers) throws SQLException {
    long startTimeMs = System.currentTimeMillis();
    logger.trace("Start updating containers={} for accountId={} into MySql DB", containers, account.getId());

    // Get list of added and updated containers in the account.
    Pair<List<Container>, List<Container>> addedOrUpdatedContainers = getUpdatedContainers(account, containers);
    AccountUpdateInfo accountUpdateInfo =
        new AccountUpdateInfo(account, false, false, addedOrUpdatedContainers.getFirst(),
            addedOrUpdatedContainers.getSecond());

    // Write changes to MySql db.
    mySqlAccountStore.updateAccounts(Collections.singletonList(accountUpdateInfo), disableGenerateColumn);

    long timeForUpdate = System.currentTimeMillis() - startTimeMs;
    logger.trace("Completed updating containers={} for accountId={} in MySqlDB in time={} ms", containers,
        account.getId(), timeForUpdate);
    accountServiceMetrics.updateAccountTimeInMs.update(timeForUpdate);
  }

  /**
   * Gets lists of added and updated {@link Container}s in the {@link Account} by comparing with in-memory cache.
   * @param account Id of the account
   * @param containers {@link Container}s added or updated in the account.
   * @return {@link Pair} of lists of added and updated {@link Container}s in the given {@link Account}
   */
  private Pair<List<Container>, List<Container>> getUpdatedContainers(Account account,
      Collection<Container> containers) {

    //check for account in in-memory cache
    Account accountInCache = getAccountById(account.getId());
    if (accountInCache == null) {
      throw new IllegalArgumentException("Account with ID " + account + "doesn't exist");
    }

    List<Container> addedContainers = new ArrayList<>();
    List<Container> updatedContainers = new ArrayList<>();
    for (Container containerToUpdate : containers) {
      Container containerInCache = accountInCache.getContainerById(containerToUpdate.getId());
      if (containerInCache == null) {
        addedContainers.add(containerToUpdate);
      } else {
        if (!containerInCache.equals(containerToUpdate)) {
          updatedContainers.add(containerToUpdate);
        }
      }
    }

    return new Pair<>(addedContainers, updatedContainers);
  }

  /**
   * A helper function to get account and container id from its name.
   * @param accountName the name of the account.
   * @param containerName the name of the container.
   * @return the pair of accountId and containerId.
   * @throws AccountServiceException
   */
  private Pair<Short, Short> getAccountAndContainerIdFromName(String accountName, String containerName)
      throws AccountServiceException {
    final Container container = getContainerByName(accountName, containerName);
    if (container == null) {
      throw new AccountServiceException("Can't find the container: " + containerName + " in account: " + accountName,
          AccountServiceErrorCode.BadRequest);
    }
    final short accountId = container.getParentAccountId();
    final short containerId = container.getId();
    return new Pair<>(accountId, containerId);
  }

  /**
   * Handle a {@link SQLException} and throw the corresponding {@link AccountServiceException}.
   * @param e the input exception.
   * @throws AccountServiceException the translated {@link AccountServiceException}.
   */
  private void handleSQLException(SQLException e) throws AccountServiceException {
    // record failure, parse exception to figure out what we did wrong (eg. id or name collision). If it is id collision,
    // retry with incrementing ID (Account ID generation logic is currently in nuage-ambry, we might need to move it here)
    accountServiceMetrics.updateAccountErrorCount.inc();
    AccountServiceException ase = translateSQLException(e);
    if (ase.getErrorCode() == AccountServiceErrorCode.ResourceConflict) {
      needRefresh = true;
    }
    throw ase;
  }
}
