/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.spring.coordination;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.simplepoint.plugin.api.PluginOperationEvent;
import org.simplepoint.plugin.api.management.PluginOperation;
import org.simplepoint.plugin.api.management.PluginOperationOutcome;
import org.simplepoint.plugin.spring.configuration.PluginRuntimeEventProperties;
import org.simplepoint.plugin.spring.jdbc.JdbcPluginSqlNames;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JDBC-backed plugin operation event log.
 */
public final class JdbcPluginOperationEventStore {

  private static final String PLUGIN_ID_SEPARATOR = "\n";

  private final JdbcOperations jdbc;
  private final String tableName;

  /**
   * Creates a JDBC plugin operation event store.
   *
   * @param dataSource data source
   * @param properties runtime event properties
   */
  public JdbcPluginOperationEventStore(DataSource dataSource, PluginRuntimeEventProperties properties) {
    this(
        new JdbcTemplate(dataSource),
        properties.getJdbc().getTableName(),
        properties.getJdbc().isInitializeSchema());
  }

  /**
   * Creates a JDBC plugin operation event store.
   *
   * @param jdbc             JDBC operations
   * @param tableName        event table name
   * @param initializeSchema whether to initialize the event table
   */
  public JdbcPluginOperationEventStore(JdbcOperations jdbc, String tableName, boolean initializeSchema) {
    this.jdbc = jdbc;
    this.tableName = JdbcPluginSqlNames.validateTableName(tableName, "plugin runtime event");
    if (initializeSchema) {
      initializeSchema();
    }
  }

  /**
   * Appends a plugin operation event.
   *
   * @param originId event origin id
   * @param event    plugin operation event
   */
  public void append(String originId, PluginOperationEvent event) {
    if (event == null) {
      return;
    }
    jdbc.update(
        "insert into " + tableName
            + " (id, origin_id, operation, outcome, plugin_ids, source, operation_id, occurred_at, message) "
            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID().toString(),
        requireText(originId, "originId"),
        event.operation().name(),
        event.outcome().name(),
        encodePluginIds(event.pluginIds()),
        event.source() == null ? null : event.source().toString(),
        event.operationId(),
        timestamp(event.occurredAt()),
        event.message());
  }

  /**
   * Lists events after a cursor, excluding events from the current origin.
   *
   * @param occurredAfter   cursor event time
   * @param eventIdAfter    cursor event id
   * @param excludedOriginId event origin id to exclude
   * @param limit           maximum number of events
   * @return stored plugin operation events
   */
  public List<JdbcPluginStoredOperationEvent> listAfter(
      Instant occurredAfter,
      String eventIdAfter,
      String excludedOriginId,
      int limit
  ) {
    Instant cursorTime = occurredAfter == null ? Instant.EPOCH : occurredAfter;
    String cursorEventId = eventIdAfter == null ? "" : eventIdAfter;
    return jdbc.query(
        "select id, origin_id, operation, outcome, plugin_ids, source, operation_id, occurred_at, message from "
            + tableName
            + " where origin_id <> ? and (occurred_at > ? or (occurred_at = ? and id > ?)) "
            + "order by occurred_at asc, id asc limit ?",
        this::mapStoredEvent,
        requireText(excludedOriginId, "excludedOriginId"),
        timestamp(cursorTime),
        timestamp(cursorTime),
        cursorEventId,
        Math.max(1, limit));
  }

  private void initializeSchema() {
    jdbc.execute("create table if not exists " + tableName + " ("
        + "id varchar(128) primary key, "
        + "origin_id varchar(255) not null, "
        + "operation varchar(64) not null, "
        + "outcome varchar(64) not null, "
        + "plugin_ids varchar(2048), "
        + "source varchar(2048), "
        + "operation_id varchar(128) not null, "
        + "occurred_at timestamp not null, "
        + "message varchar(2048)"
        + ")");
  }

  private JdbcPluginStoredOperationEvent mapStoredEvent(ResultSet resultSet, int rowNumber) throws SQLException {
    String source = resultSet.getString("source");
    PluginOperationEvent event = new PluginOperationEvent(
        PluginOperation.valueOf(resultSet.getString("operation")),
        PluginOperationOutcome.valueOf(resultSet.getString("outcome")),
        decodePluginIds(resultSet.getString("plugin_ids")),
        source == null ? null : URI.create(source),
        resultSet.getString("operation_id"),
        instant(resultSet.getTimestamp("occurred_at")),
        resultSet.getString("message"));
    return new JdbcPluginStoredOperationEvent(
        resultSet.getString("id"),
        resultSet.getString("origin_id"),
        event);
  }

  private static String encodePluginIds(List<String> pluginIds) {
    if (pluginIds == null || pluginIds.isEmpty()) {
      return "";
    }
    return String.join(PLUGIN_ID_SEPARATOR, pluginIds);
  }

  private static List<String> decodePluginIds(String pluginIds) {
    if (!hasText(pluginIds)) {
      return List.of();
    }
    return Arrays.stream(pluginIds.split(PLUGIN_ID_SEPARATOR))
        .filter(JdbcPluginOperationEventStore::hasText)
        .toList();
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

  private static Instant instant(Timestamp timestamp) {
    return timestamp.toInstant();
  }
}
