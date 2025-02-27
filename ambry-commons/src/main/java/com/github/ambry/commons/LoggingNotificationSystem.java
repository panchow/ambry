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
package com.github.ambry.commons;

import com.github.ambry.account.Account;
import com.github.ambry.account.Container;
import com.github.ambry.clustermap.DataNodeId;
import com.github.ambry.messageformat.BlobProperties;
import com.github.ambry.notification.BlobReplicaSourceType;
import com.github.ambry.notification.NotificationBlobType;
import com.github.ambry.notification.NotificationSystem;
import com.github.ambry.notification.UpdateType;
import com.github.ambry.store.MessageInfo;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Logs all events at DEBUG level.
 */
public class LoggingNotificationSystem implements NotificationSystem {
  private static final Logger logger = LoggerFactory.getLogger(LoggingNotificationSystem.class);

  @Override
  public void close() throws IOException {
    // No op
  }

  @Override
  public void onBlobCreated(String blobId, BlobProperties blobProperties, Account account, Container container,
      NotificationBlobType notificationBlobType) {
    logger.debug(
        "onBlobCreated {}, blobProperties {}, accountName {}, accountId{}, containerName {}, containerId {}, blobType {}",
        blobId, blobProperties, account == null ? null : account.getName(), account == null ? null : account.getId(),
        container == null ? null : container.getName(), container == null ? null : container.getId(),
        notificationBlobType);
  }

  @Override
  public void onBlobTtlUpdated(String blobId, String serviceId, long expiresAtMs, Account account,
      Container container) {
    logger.debug("onBlobTtlUpdated {}, serviceId {}, accountName {}, accountId{}, containerName {}, containerId {}, {}",
        blobId, serviceId, account == null ? null : account.getName(), account == null ? null : account.getId(),
        container == null ? null : container.getName(), container == null ? null : container.getId(), expiresAtMs);
  }

  @Override
  public void onBlobDeleted(String blobId, String serviceId, Account account, Container container) {
    logger.debug("onBlobDeleted {}, serviceId {}, accountName {}, accountId {}, containerName {}, containerId {}",
        blobId, serviceId, account == null ? null : account.getName(), account == null ? null : account.getId(),
        container == null ? null : container.getName(), container == null ? null : container.getId());
  }

  @Override
  public void onBlobUndeleted(String blobId, String serviceId, Account account, Container container) {
    logger.debug("onBlobUndeleted {}, serviceId {}, accountName {}, accountId {}, containerName {}, containerId {}",
        blobId, serviceId, account == null ? null : account.getName(), account == null ? null : account.getId(),
        container == null ? null : container.getName(), container == null ? null : container.getId());
  }

  @Override
  public void onBlobReplicated(String blobId, String serviceId, Account account, Container container,
      DataNodeId sourceHost) {
    logger.debug(
        "onBlobReplicated {}, serviceId {}, accountName {}, accountId {}, containerName {}, containerId {}, host {} {}",
        blobId, serviceId, account == null ? null : account.getName(), account == null ? null : account.getId(),
        container == null ? null : container.getName(), container == null ? null : container.getId(),
        sourceHost.getHostname(), sourceHost.getPort());
  }

  @Override
  public void onBlobReplicaCreated(String sourceHost, int port, String blobId, BlobReplicaSourceType sourceType) {
    logger.debug("onBlobReplicaCreated {}, {}, {}, {}", sourceHost, port, blobId, sourceType);
  }

  @Override
  public void onBlobReplicaDeleted(String sourceHost, int port, String blobId, BlobReplicaSourceType sourceType) {
    logger.debug("onBlobReplicaDeleted {}, {}, {}, {}", sourceHost, port, blobId, sourceType);
  }

  @Override
  public void onBlobReplicaUpdated(String sourceHost, int port, String blobId, BlobReplicaSourceType sourceType,
      UpdateType updateType, MessageInfo info) {
    logger.debug("onBlobReplicaUpdated {}, {}, {}, {}, {}, {}", sourceHost, port, blobId, sourceType, updateType, info);
  }

  @Override
  public void onBlobReplicaUndeleted(String sourceHost, int port, String blobId, BlobReplicaSourceType sourceType) {
    logger.debug("onBlobReplicaUndeleted {}, {}, {}, {}", sourceHost, port, blobId, sourceType);
  }

  @Override
  public void onBlobReplicaReplicated(String sourceHost, int port, String blobId, BlobReplicaSourceType sourceType) {
    logger.debug("onBlobReplicaReplicated {}, {}, {}, {}", sourceHost, port, blobId, sourceType);
  }
}

