/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.spring.coordination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.api.PluginOperationContext;
import org.simplepoint.plugin.api.management.PluginOperation;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcPluginRuntimeCoordinatorTest {

  @Test
  void coordinateExecutesCallbackAndReleasesLock() throws Exception {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource());
    JdbcPluginRuntimeCoordinator coordinator = coordinator(jdbc, "node-a", Duration.ofMillis(100));

    String result = coordinator.coordinate(context("operation-1"), () -> "ok");
    String next = coordinator.coordinate(context("operation-2"), () -> "next");

    assertThat(result).isEqualTo("ok");
    assertThat(next).isEqualTo("next");
    assertThat(coordinator.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
  }

  @Test
  void coordinateTimesOutWhenAnotherOwnerHoldsTheLock() throws Exception {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource());
    JdbcPluginRuntimeCoordinator holder = coordinator(jdbc, "node-a", Duration.ofMillis(500));
    JdbcPluginRuntimeCoordinator waiter = coordinator(jdbc, "node-b", Duration.ofMillis(40));

    String result = holder.coordinate(context("holder"), () -> {
      assertThatThrownBy(() -> waiter.coordinate(context("waiter"), () -> "blocked"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Timed out acquiring plugin runtime lock");
      return "held";
    });

    assertThat(result).isEqualTo("held");
    assertThat(waiter.coordinate(context("waiter-after-release"), () -> "acquired")).isEqualTo("acquired");
  }

  @Test
  void coordinateReleasesLockAfterCallbackFailure() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource());
    JdbcPluginRuntimeCoordinator failing = coordinator(jdbc, "node-a", Duration.ofMillis(100));
    JdbcPluginRuntimeCoordinator next = coordinator(jdbc, "node-b", Duration.ofMillis(100));

    assertThatThrownBy(() -> failing.coordinate(context("failing"), () -> {
      throw new IllegalStateException("boom");
    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("boom");

    assertThatCode(() -> next.coordinate(context("after-failure"), () -> "ok")).doesNotThrowAnyException();
  }

  @Test
  void constructorRejectsUnsafeTableNames() {
    assertThatThrownBy(() -> new JdbcPluginRuntimeCoordinator(
        new JdbcTemplate(dataSource()),
        "sp_plugin_runtime_lock; drop table users",
        "plugin-runtime",
        "node-a",
        Duration.ofSeconds(1),
        Duration.ofMillis(100),
        Duration.ofMillis(10),
        false,
        Clock.systemUTC()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid plugin runtime lock table name");
  }

  private static JdbcPluginRuntimeCoordinator coordinator(
      JdbcTemplate jdbc,
      String ownerId,
      Duration acquireTimeout
  ) {
    return new JdbcPluginRuntimeCoordinator(
        jdbc,
        "sp_plugin_runtime_lock",
        "plugin-runtime",
        ownerId,
        Duration.ofSeconds(5),
        acquireTimeout,
        Duration.ofMillis(5),
        true,
        Clock.systemUTC());
  }

  private static PluginOperationContext context(String operationId) {
    return new PluginOperationContext(
        PluginOperation.INSTALL,
        List.of("org.example.plugin"),
        null,
        operationId,
        Instant.now());
  }

  private static JdbcDataSource dataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:plugin-runtime-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    return dataSource;
  }
}
