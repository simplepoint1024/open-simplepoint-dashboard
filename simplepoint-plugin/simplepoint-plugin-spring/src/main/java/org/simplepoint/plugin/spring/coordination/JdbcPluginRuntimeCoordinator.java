/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.spring.coordination;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.simplepoint.plugin.api.PluginOperationCallback;
import org.simplepoint.plugin.api.PluginOperationContext;
import org.simplepoint.plugin.api.PluginRuntimeCoordinator;
import org.simplepoint.plugin.spring.configuration.PluginRuntimeCoordinatorProperties;
import org.simplepoint.plugin.spring.jdbc.JdbcPluginSqlNames;
import org.springframework.core.Ordered;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JDBC-backed plugin runtime coordinator.
 */
public final class JdbcPluginRuntimeCoordinator implements PluginRuntimeCoordinator, Ordered {

  private final JdbcOperations jdbc;
  private final String tableName;
  private final String lockName;
  private final String ownerId;
  private final Duration leaseDuration;
  private final Duration acquireTimeout;
  private final Duration retryInterval;
  private final Clock clock;

  /**
   * Creates a JDBC plugin runtime coordinator.
   *
   * @param dataSource data source
   * @param properties runtime coordinator properties
   */
  public JdbcPluginRuntimeCoordinator(DataSource dataSource, PluginRuntimeCoordinatorProperties properties) {
    this(
        new JdbcTemplate(dataSource),
        properties.getJdbc().getTableName(),
        properties.getJdbc().getLockName(),
        properties.getJdbc().getOwnerId(),
        properties.getJdbc().getLeaseDuration(),
        properties.getJdbc().getAcquireTimeout(),
        properties.getJdbc().getRetryInterval(),
        properties.getJdbc().isInitializeSchema(),
        Clock.systemUTC());
  }

  /**
   * Creates a JDBC plugin runtime coordinator.
   *
   * @param jdbc             JDBC operations
   * @param tableName        lock table name
   * @param lockName         logical lock name
   * @param ownerId          runtime owner id
   * @param leaseDuration    lock lease duration
   * @param acquireTimeout   lock acquisition timeout
   * @param retryInterval    lock acquisition retry interval
   * @param initializeSchema whether to initialize the lock table
   * @param clock            clock
   */
  public JdbcPluginRuntimeCoordinator(
      JdbcOperations jdbc,
      String tableName,
      String lockName,
      String ownerId,
      Duration leaseDuration,
      Duration acquireTimeout,
      Duration retryInterval,
      boolean initializeSchema,
      Clock clock
  ) {
    this.jdbc = jdbc;
    this.tableName = JdbcPluginSqlNames.validateTableName(tableName, "plugin runtime lock");
    this.lockName = requireText(lockName, "lockName");
    this.ownerId = hasText(ownerId) ? ownerId : "node-" + UUID.randomUUID();
    this.leaseDuration = requirePositive(leaseDuration, "leaseDuration");
    this.acquireTimeout = requirePositive(acquireTimeout, "acquireTimeout");
    this.retryInterval = requirePositive(retryInterval, "retryInterval");
    this.clock = clock == null ? Clock.systemUTC() : clock;
    if (initializeSchema) {
      initializeSchema();
    }
  }

  @Override
  public <T> T coordinate(PluginOperationContext context, PluginOperationCallback<T> callback) throws Exception {
    acquire(context);
    try {
      return callback.execute();
    } finally {
      release(context);
    }
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  private void initializeSchema() {
    jdbc.execute("create table if not exists " + tableName + " ("
        + "lock_name varchar(128) primary key, "
        + "owner_id varchar(255) not null, "
        + "operation_id varchar(128) not null, "
        + "locked_until timestamp not null, "
        + "updated_at timestamp not null"
        + ")");
  }

  private void acquire(PluginOperationContext context) {
    Instant deadline = clock.instant().plus(acquireTimeout);
    while (true) {
      Instant now = clock.instant();
      if (tryAcquire(context, now)) {
        return;
      }
      if (!now.isBefore(deadline)) {
        throw new IllegalStateException("Timed out acquiring plugin runtime lock: " + lockName);
      }
      sleepUntilNextAttempt(deadline);
    }
  }

  private boolean tryAcquire(PluginOperationContext context, Instant now) {
    Instant lockedUntil = now.plus(leaseDuration);
    int updated = jdbc.update(
        "update " + tableName + " set owner_id = ?, operation_id = ?, locked_until = ?, updated_at = ? "
            + "where lock_name = ? and (locked_until <= ? or (owner_id = ? and operation_id = ?))",
        ownerId,
        context.operationId(),
        timestamp(lockedUntil),
        timestamp(now),
        lockName,
        timestamp(now),
        ownerId,
        context.operationId());
    if (updated > 0) {
      return true;
    }
    try {
      jdbc.update(
          "insert into " + tableName
              + " (lock_name, owner_id, operation_id, locked_until, updated_at) values (?, ?, ?, ?, ?)",
          lockName,
          ownerId,
          context.operationId(),
          timestamp(lockedUntil),
          timestamp(now));
      return true;
    } catch (DuplicateKeyException ignored) {
      return false;
    }
  }

  private void release(PluginOperationContext context) {
    Instant now = clock.instant();
    jdbc.update(
        "update " + tableName + " set locked_until = ?, updated_at = ? "
            + "where lock_name = ? and owner_id = ? and operation_id = ?",
        timestamp(now),
        timestamp(now),
        lockName,
        ownerId,
        context.operationId());
  }

  private void sleepUntilNextAttempt(Instant deadline) {
    long remainingMillis = Math.max(1, Duration.between(clock.instant(), deadline).toMillis());
    long retryMillis = Math.max(1, retryInterval.toMillis());
    long sleepMillis = Math.min(retryMillis, remainingMillis);
    try {
      Thread.sleep(sleepMillis);
    } catch (InterruptedException interruptedException) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while acquiring plugin runtime lock: " + lockName,
          interruptedException);
    }
  }

  private static Duration requirePositive(Duration duration, String name) {
    if (duration == null || duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException(name + " must be greater than zero");
    }
    return duration;
  }

  private static String requireText(String value, String name) {
    if (!hasText(value)) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value.trim();
  }

  private static boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.from(instant);
  }
}
