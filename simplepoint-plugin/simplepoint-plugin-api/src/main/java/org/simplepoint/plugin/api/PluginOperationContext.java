/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.api;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.simplepoint.plugin.api.management.PluginOperation;

/**
 * Context for a coordinated plugin management operation.
 *
 * @param operation   plugin operation type
 * @param pluginIds   requested plugin ids, if known before execution
 * @param source      artifact or directory URI related to the operation
 * @param operationId unique operation id for lock/event correlation
 * @param startedAt   operation start time
 */
public record PluginOperationContext(
    PluginOperation operation,
    List<String> pluginIds,
    URI source,
    String operationId,
    Instant startedAt
) {

  /**
   * Creates an immutable plugin operation context.
   */
  public PluginOperationContext {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(startedAt, "startedAt");
    pluginIds = List.copyOf(Objects.requireNonNullElse(pluginIds, List.of()));
  }
}
