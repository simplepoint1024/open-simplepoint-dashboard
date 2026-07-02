/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.spring.coordination;

import org.simplepoint.plugin.api.PluginOperationCallback;
import org.simplepoint.plugin.api.PluginOperationContext;
import org.simplepoint.plugin.api.PluginOperationEvent;
import org.simplepoint.plugin.api.PluginRuntimeCoordinator;
import org.simplepoint.plugin.spring.configuration.PluginRuntimeEventProperties;
import org.springframework.core.Ordered;

/**
 * Records local plugin operation events into a JDBC event log.
 */
public final class JdbcPluginOperationEventRecorder implements PluginRuntimeCoordinator, Ordered {

  private final JdbcPluginOperationEventStore eventStore;
  private final String originId;

  /**
   * Creates a JDBC plugin operation event recorder.
   *
   * @param eventStore JDBC operation event store
   * @param properties runtime event properties
   */
  public JdbcPluginOperationEventRecorder(
      JdbcPluginOperationEventStore eventStore,
      PluginRuntimeEventProperties properties
  ) {
    this(eventStore, properties.getJdbc().getOriginId());
  }

  /**
   * Creates a JDBC plugin operation event recorder.
   *
   * @param eventStore JDBC operation event store
   * @param originId   event origin id
   */
  public JdbcPluginOperationEventRecorder(JdbcPluginOperationEventStore eventStore, String originId) {
    this.eventStore = eventStore;
    this.originId = requireText(originId, "originId");
  }

  @Override
  public <T> T coordinate(PluginOperationContext context, PluginOperationCallback<T> callback) throws Exception {
    return callback.execute();
  }

  @Override
  public void publish(PluginOperationEvent event) {
    eventStore.append(originId, event);
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE - 100;
  }

  String originId() {
    return originId;
  }

  private static String requireText(String value, String name) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value.trim();
  }
}
