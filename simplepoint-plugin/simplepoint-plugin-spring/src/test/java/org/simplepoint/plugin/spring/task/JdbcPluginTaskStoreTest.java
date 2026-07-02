/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.spring.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.api.management.PluginOperation;
import org.simplepoint.plugin.api.management.PluginTaskSnapshot;
import org.simplepoint.plugin.api.management.PluginTaskStatus;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcPluginTaskStoreTest {

  @Test
  void saveInsertsAndUpdatesTaskSnapshots() {
    JdbcPluginTaskStore store = new JdbcPluginTaskStore(
        new JdbcTemplate(dataSource()),
        "sp_plugin_task",
        true);
    Instant createdAt = Instant.parse("2026-07-02T00:00:00Z");
    Instant updatedAt = Instant.parse("2026-07-02T00:00:01Z");

    store.save(task("task-1", PluginTaskStatus.PENDING, 0, createdAt, createdAt, null));
    store.save(task("task-1", PluginTaskStatus.SUCCEEDED, 1, createdAt, updatedAt, null));

    assertThat(store.list())
        .singleElement()
        .satisfies(task -> {
          assertThat(task.id()).isEqualTo("task-1");
          assertThat(task.pluginId()).isEqualTo("org.example.plugin");
          assertThat(task.operation()).isEqualTo(PluginOperation.SUBMIT);
          assertThat(task.status()).isEqualTo(PluginTaskStatus.SUCCEEDED);
          assertThat(task.attempts()).isEqualTo(1);
          assertThat(task.createdAt()).isEqualTo(createdAt);
          assertThat(task.updatedAt()).isEqualTo(updatedAt);
          assertThat(task.failure()).isNull();
        });
  }

  @Test
  void listReturnsTasksOrderedByCreationTime() {
    JdbcPluginTaskStore store = new JdbcPluginTaskStore(
        new JdbcTemplate(dataSource()),
        "sp_plugin_task",
        true);

    store.save(task("second", PluginTaskStatus.PENDING, 0, Instant.parse("2026-07-02T00:00:02Z"),
        Instant.parse("2026-07-02T00:00:02Z"), null));
    store.save(task("first", PluginTaskStatus.FAILED, 10, Instant.parse("2026-07-02T00:00:01Z"),
        Instant.parse("2026-07-02T00:00:03Z"), "boom"));

    assertThat(store.list())
        .extracting(PluginTaskSnapshot::id)
        .containsExactly("first", "second");
    assertThat(store.list().get(0).failure()).isEqualTo("boom");
  }

  @Test
  void constructorRejectsUnsafeTableNames() {
    assertThatThrownBy(() -> new JdbcPluginTaskStore(
        new JdbcTemplate(dataSource()),
        "sp_plugin_task; drop table users",
        false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid plugin task table name");
  }

  private static JdbcDataSource dataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:plugin-task-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    return dataSource;
  }

  private static PluginTaskSnapshot task(
      String id,
      PluginTaskStatus status,
      int attempts,
      Instant createdAt,
      Instant updatedAt,
      String failure
  ) {
    return new PluginTaskSnapshot(
        id,
        "org.example.plugin",
        PluginOperation.SUBMIT,
        status,
        attempts,
        createdAt,
        updatedAt,
        failure);
  }
}
