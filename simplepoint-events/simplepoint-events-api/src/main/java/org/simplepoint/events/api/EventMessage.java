/*
 * Copyright (c) 2026 SimplePoint
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.events.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Versioned, transport independent business event; never contains an authentication snapshot. */
public record EventMessage(UUID eventId, String eventType, int schemaVersion, String producer,
                           String tenantId, String resourceId, long generation,
                           String operationId, String correlationId, Instant occurredAt,
                           JsonNode payload) {
  /** Validates the envelope and snapshots its mutable JSON payload. */
  public EventMessage {
    Objects.requireNonNull(eventId, "eventId");
    token(eventType, "eventType");
    token(producer, "producer");
    text(tenantId, "tenantId");
    text(resourceId, "resourceId");
    text(operationId, "operationId");
    text(correlationId, "correlationId");
    Objects.requireNonNull(occurredAt, "occurredAt");
    if (schemaVersion < 1 || generation < 0 || payload == null || !payload.isObject()) {
      throw new IllegalArgumentException("Invalid event version, generation or object payload");
    }
    payload = payload.deepCopy();
  }

  @Override
  public JsonNode payload() {
    return payload.deepCopy();
  }

  /** Validates names used in broker routing and stable subscriptions. */
  public static String token(String value, String name) {
    if (value == null || !value.matches("[a-z][a-z0-9.-]{0,95}")) {
      throw new IllegalArgumentException("Invalid " + name);
    }
    return value;
  }

  private static void text(String value, String name) {
    if (value == null || value.isBlank() || value.length() > 160) {
      throw new IllegalArgumentException("Invalid " + name);
    }
  }
}
