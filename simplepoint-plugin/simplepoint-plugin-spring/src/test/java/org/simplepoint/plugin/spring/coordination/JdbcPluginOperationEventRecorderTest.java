/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.spring.coordination;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.api.PluginOperationContext;
import org.simplepoint.plugin.api.PluginOperationEvent;
import org.simplepoint.plugin.api.management.PluginOperation;
import org.simplepoint.plugin.api.management.PluginOperationOutcome;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcPluginOperationEventRecorderTest {

  @Test
  void coordinateDelegatesToCallback() throws Exception {
    JdbcPluginOperationEventRecorder recorder = new JdbcPluginOperationEventRecorder(store(), "node-a");
    PluginOperationContext context = new PluginOperationContext(
        PluginOperation.INSTALL,
        List.of("org.example.plugin"),
        null,
        "operation-1",
        Instant.now());

    String result = recorder.coordinate(context, () -> "ok");

    assertThat(result).isEqualTo("ok");
  }

  @Test
  void publishAppendsEventWithOriginId() {
    JdbcPluginOperationEventStore store = store();
    JdbcPluginOperationEventRecorder recorder = new JdbcPluginOperationEventRecorder(store, "node-a");
    PluginOperationEvent event = new PluginOperationEvent(
        PluginOperation.SUBMIT,
        PluginOperationOutcome.SUCCESS,
        List.of("org.example.plugin"),
        null,
        "operation-1",
        Instant.parse("2026-07-02T00:00:00Z"),
        null);

    recorder.publish(event);

    assertThat(store.listAfter(Instant.EPOCH, "", "node-b", 10))
        .singleElement()
        .satisfies(stored -> {
          assertThat(stored.originId()).isEqualTo("node-a");
          assertThat(stored.event()).isEqualTo(event);
        });
    assertThat(recorder.originId()).isEqualTo("node-a");
    assertThat(recorder.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE - 100);
  }

  private static JdbcPluginOperationEventStore store() {
    return new JdbcPluginOperationEventStore(
        new JdbcTemplate(dataSource()),
        "sp_plugin_runtime_event",
        true);
  }

  private static JdbcDataSource dataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:plugin-runtime-event-recorder-" + UUID.randomUUID()
        + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    return dataSource;
  }
}
