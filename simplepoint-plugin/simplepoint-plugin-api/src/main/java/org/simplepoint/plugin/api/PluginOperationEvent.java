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
import org.simplepoint.plugin.api.management.PluginOperationOutcome;

/**
 * Event emitted after a coordinated plugin operation completes.
 *
 * @param operation   plugin operation type
 * @param outcome     operation outcome
 * @param pluginIds   affected plugin ids, if known
 * @param source      artifact or directory URI related to the operation
 * @param operationId operation id copied from the coordination context
 * @param occurredAt  event time
 * @param message     failure or diagnostic message
 */
public record PluginOperationEvent(
    PluginOperation operation,
    PluginOperationOutcome outcome,
    List<String> pluginIds,
    URI source,
    String operationId,
    Instant occurredAt,
    String message
) {

  /**
   * Creates an immutable plugin operation event.
   */
  public PluginOperationEvent {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(occurredAt, "occurredAt");
    pluginIds = List.copyOf(Objects.requireNonNullElse(pluginIds, List.of()));
  }
}
