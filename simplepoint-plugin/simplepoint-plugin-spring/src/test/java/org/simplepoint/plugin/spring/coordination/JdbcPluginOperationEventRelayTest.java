/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.spring.coordination;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.api.PluginOperationEvent;
import org.simplepoint.plugin.api.management.PluginOperation;
import org.simplepoint.plugin.api.management.PluginOperationOutcome;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcPluginOperationEventRelayTest {

  @Test
  void pollOnceRelaysOnlyEventsFromOtherOrigins() {
    JdbcPluginOperationEventStore store = store();
    List<Object> published = new ArrayList<>();
    Instant relayStartedAt = Instant.parse("2026-07-02T00:00:00Z");
    JdbcPluginOperationEventRelay relay = relay(store, published, false, relayStartedAt);
    PluginOperationEvent local = event("local", relayStartedAt.plusSeconds(1));
    PluginOperationEvent remote = event("remote", relayStartedAt.plusSeconds(2));

    store.append("node-a", local);
    store.append("node-b", remote);

    assertThat(relay.pollOnce()).isEqualTo(1);
    assertThat(published).containsExactly(remote);
    assertThat(relay.pollOnce()).isZero();
  }

  @Test
  void pollOnceSkipsExistingEventsUnlessReplayIsEnabled() {
    JdbcPluginOperationEventStore store = store();
    List<Object> published = new ArrayList<>();
    Instant relayStartedAt = Instant.parse("2026-07-02T00:00:10Z");
    PluginOperationEvent existing = event("existing", relayStartedAt.minusSeconds(1));
    store.append("node-b", existing);

    JdbcPluginOperationEventRelay relayWithoutReplay = relay(store, published, false, relayStartedAt);
    assertThat(relayWithoutReplay.pollOnce()).isZero();
    assertThat(published).isEmpty();

    JdbcPluginOperationEventRelay relayWithReplay = relay(store, published, true, relayStartedAt);
    assertThat(relayWithReplay.pollOnce()).isEqualTo(1);
    assertThat(published).containsExactly(existing);
  }

  private static JdbcPluginOperationEventRelay relay(
      JdbcPluginOperationEventStore store,
      List<Object> published,
      boolean replayExisting,
      Instant relayStartedAt
  ) {
    return new JdbcPluginOperationEventRelay(
        store,
        published::add,
        "node-a",
        Duration.ofSeconds(1),
        10,
        replayExisting,
        Clock.fixed(relayStartedAt, ZoneOffset.UTC));
  }

  private static JdbcPluginOperationEventStore store() {
    return new JdbcPluginOperationEventStore(
        new JdbcTemplate(dataSource()),
        "sp_plugin_runtime_event",
        true);
  }

  private static PluginOperationEvent event(String operationId, Instant occurredAt) {
    return new PluginOperationEvent(
        PluginOperation.ENABLE,
        PluginOperationOutcome.SUCCESS,
        List.of("org.example.plugin"),
        null,
        operationId,
        occurredAt,
        null);
  }

  private static JdbcDataSource dataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:plugin-runtime-event-relay-" + UUID.randomUUID()
        + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    return dataSource;
  }
}
