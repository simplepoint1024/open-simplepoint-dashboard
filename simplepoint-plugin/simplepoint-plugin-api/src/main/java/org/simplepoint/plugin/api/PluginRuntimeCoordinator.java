/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.api;

/**
 * Coordinates plugin runtime operations across one or more application nodes.
 *
 * <p>Implementations can provide distributed locking, operation fencing, event
 * publication, or external state reconciliation. The core runtime only depends
 * on this small contract, so Redis, JDBC, MQ, or service-discovery backed
 * implementations can be added without coupling them to plugin loading logic.
 */
public interface PluginRuntimeCoordinator {

  /**
   * Executes a plugin operation inside the coordinator boundary.
   *
   * @param context  operation context
   * @param callback operation callback
   * @param <T>      callback result type
   * @return callback result
   * @throws Exception if the operation or coordinator fails
   */
  <T> T coordinate(PluginOperationContext context, PluginOperationCallback<T> callback) throws Exception;

  /**
   * Publishes an operation completion event.
   *
   * @param event operation event
   */
  default void publish(PluginOperationEvent event) {
  }
}
