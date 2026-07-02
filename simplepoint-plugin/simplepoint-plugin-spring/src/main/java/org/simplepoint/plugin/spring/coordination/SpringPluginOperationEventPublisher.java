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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;

/**
 * Publishes plugin operation events through Spring's application event bus.
 */
public final class SpringPluginOperationEventPublisher implements PluginRuntimeCoordinator, Ordered {

  private final ApplicationEventPublisher eventPublisher;

  /**
   * Creates a Spring event publisher coordinator.
   *
   * @param eventPublisher Spring application event publisher
   */
  public SpringPluginOperationEventPublisher(ApplicationEventPublisher eventPublisher) {
    this.eventPublisher = eventPublisher;
  }

  @Override
  public <T> T coordinate(PluginOperationContext context, PluginOperationCallback<T> callback) throws Exception {
    return callback.execute();
  }

  @Override
  public void publish(PluginOperationEvent event) {
    eventPublisher.publishEvent(event);
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
