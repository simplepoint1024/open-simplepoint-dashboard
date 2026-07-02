/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.spring.task;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.simplepoint.plugin.api.PluginTaskStore;
import org.simplepoint.plugin.api.management.PluginOperation;
import org.simplepoint.plugin.api.management.PluginTaskSnapshot;
import org.simplepoint.plugin.api.management.PluginTaskStatus;
import org.simplepoint.plugin.spring.configuration.PluginTaskStoreProperties;
import org.simplepoint.plugin.spring.jdbc.JdbcPluginSqlNames;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JDBC-backed plugin task store.
 */
public final class JdbcPluginTaskStore implements PluginTaskStore {

  private final JdbcOperations jdbc;
  private final String tableName;

  /**
   * Creates a JDBC plugin task store.
   *
   * @param dataSource data source
   * @param properties task store properties
   */
  public JdbcPluginTaskStore(DataSource dataSource, PluginTaskStoreProperties properties) {
    this(
        new JdbcTemplate(dataSource),
        properties.getJdbc().getTableName(),
        properties.getJdbc().isInitializeSchema());
  }

  /**
   * Creates a JDBC plugin task store.
   *
   * @param jdbc             JDBC operations
   * @param tableName        task table name
   * @param initializeSchema whether to initialize the task table
   */
  public JdbcPluginTaskStore(JdbcOperations jdbc, String tableName, boolean initializeSchema) {
    this.jdbc = jdbc;
    this.tableName = JdbcPluginSqlNames.validateTableName(tableName, "plugin task");
    if (initializeSchema) {
      initializeSchema();
    }
  }

  @Override
  public void save(PluginTaskSnapshot snapshot) {
    if (snapshot == null) {
      return;
    }
    int updated = jdbc.update(
        "update " + tableName + " set plugin_id = ?, operation = ?, status = ?, attempts = ?, "
            + "created_at = ?, updated_at = ?, failure = ? where id = ?",
        snapshot.pluginId(),
        snapshot.operation().name(),
        snapshot.status().name(),
        snapshot.attempts(),
        timestamp(snapshot.createdAt()),
        timestamp(snapshot.updatedAt()),
        snapshot.failure(),
        snapshot.id());
    if (updated == 0) {
      insert(snapshot);
    }
  }

  @Override
  public List<PluginTaskSnapshot> list() {
    return jdbc.query(
        "select id, plugin_id, operation, status, attempts, created_at, updated_at, failure from "
            + tableName + " order by created_at asc, id asc",
        this::mapTask);
  }

  private void initializeSchema() {
    jdbc.execute("create table if not exists " + tableName + " ("
        + "id varchar(128) primary key, "
        + "plugin_id varchar(255) not null, "
        + "operation varchar(64) not null, "
        + "status varchar(64) not null, "
        + "attempts integer not null, "
        + "created_at timestamp not null, "
        + "updated_at timestamp not null, "
        + "failure varchar(2048)"
        + ")");
  }

  private void insert(PluginTaskSnapshot snapshot) {
    try {
      jdbc.update(
          "insert into " + tableName
              + " (id, plugin_id, operation, status, attempts, created_at, updated_at, failure) "
              + "values (?, ?, ?, ?, ?, ?, ?, ?)",
          snapshot.id(),
          snapshot.pluginId(),
          snapshot.operation().name(),
          snapshot.status().name(),
          snapshot.attempts(),
          timestamp(snapshot.createdAt()),
          timestamp(snapshot.updatedAt()),
          snapshot.failure());
    } catch (DuplicateKeyException ignored) {
      save(snapshot);
    }
  }

  private PluginTaskSnapshot mapTask(ResultSet resultSet, int rowNumber) throws SQLException {
    return new PluginTaskSnapshot(
        resultSet.getString("id"),
        resultSet.getString("plugin_id"),
        PluginOperation.valueOf(resultSet.getString("operation")),
        PluginTaskStatus.valueOf(resultSet.getString("status")),
        resultSet.getInt("attempts"),
        instant(resultSet.getTimestamp("created_at")),
        instant(resultSet.getTimestamp("updated_at")),
        resultSet.getString("failure"));
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.from(instant);
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp.toInstant();
  }
}
