/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.spring.coordination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.api.PluginOperationEvent;
import org.simplepoint.plugin.api.management.PluginOperation;
import org.simplepoint.plugin.api.management.PluginOperationOutcome;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcPluginOperationEventStoreTest {

  @Test
  void appendListsEventsAfterCursorAndExcludesCurrentOrigin() {
    JdbcPluginOperationEventStore store = store();
    PluginOperationEvent local = event("operation-local", Instant.parse("2026-07-02T00:00:01Z"));
    PluginOperationEvent remote = event("operation-remote", Instant.parse("2026-07-02T00:00:02Z"));

    store.append("node-a", local);
    store.append("node-b", remote);

    List<JdbcPluginStoredOperationEvent> events = store.listAfter(Instant.EPOCH, "", "node-a", 10);

    assertThat(events)
        .singleElement()
        .satisfies(stored -> {
          assertThat(stored.originId()).isEqualTo("node-b");
          assertThat(stored.event().operation()).isEqualTo(PluginOperation.INSTALL);
          assertThat(stored.event().outcome()).isEqualTo(PluginOperationOutcome.SUCCESS);
          assertThat(stored.event().pluginIds()).containsExactly("org.example.plugin", "org.example.dependency");
          assertThat(stored.event().source()).isEqualTo(URI.create("file:/plugins/example.jar"));
          assertThat(stored.event().operationId()).isEqualTo("operation-remote");
          assertThat(stored.event().occurredAt()).isEqualTo(Instant.parse("2026-07-02T00:00:02Z"));
          assertThat(stored.event().message()).isEqualTo("done");
        });
    assertThat(store.listAfter(events.get(0).event().occurredAt(), events.get(0).id(), "node-a", 10))
        .isEmpty();
  }

  @Test
  void constructorRejectsUnsafeTableNames() {
    assertThatThrownBy(() -> new JdbcPluginOperationEventStore(
        new JdbcTemplate(dataSource()),
        "sp_plugin_runtime_event; drop table users",
        false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid plugin runtime event table name");
  }

  private static JdbcPluginOperationEventStore store() {
    return new JdbcPluginOperationEventStore(
        new JdbcTemplate(dataSource()),
        "sp_plugin_runtime_event",
        true);
  }

  private static PluginOperationEvent event(String operationId, Instant occurredAt) {
    return new PluginOperationEvent(
        PluginOperation.INSTALL,
        PluginOperationOutcome.SUCCESS,
        List.of("org.example.plugin", "org.example.dependency"),
        URI.create("file:/plugins/example.jar"),
        operationId,
        occurredAt,
        "done");
  }

  private static JdbcDataSource dataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:plugin-runtime-event-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    return dataSource;
  }
}
