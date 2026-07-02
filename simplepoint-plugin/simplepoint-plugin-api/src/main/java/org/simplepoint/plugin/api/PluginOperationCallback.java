/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.api;

/**
 * Callback executed inside a plugin operation coordination boundary.
 *
 * @param <T> callback result type
 */
@FunctionalInterface
public interface PluginOperationCallback<T> {

  /**
   * Executes the operation.
   *
   * @return operation result
   * @throws Exception if the operation fails
   */
  T execute() throws Exception;
}
