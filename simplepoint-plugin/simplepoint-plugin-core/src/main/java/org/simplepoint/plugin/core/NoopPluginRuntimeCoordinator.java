/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import org.simplepoint.plugin.api.PluginOperationCallback;
import org.simplepoint.plugin.api.PluginOperationContext;
import org.simplepoint.plugin.api.PluginRuntimeCoordinator;

/**
 * Single-node coordinator used when no external coordinator is configured.
 */
public enum NoopPluginRuntimeCoordinator implements PluginRuntimeCoordinator {

  /**
   * Shared no-op coordinator instance.
   */
  INSTANCE;

  @Override
  public <T> T coordinate(PluginOperationContext context, PluginOperationCallback<T> callback) throws Exception {
    return callback.execute();
  }
}
