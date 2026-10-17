/*
 * Copyright (c) 2026 SimplePoint
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.events.api;

/** Stable logical subscription, independent of running service instances. */
public record EventSubscription(String id, String producer, String eventType, int schemaVersion) {
  /** Validates the stable routing contract. */
  public EventSubscription {
    EventMessage.token(id, "subscription id");
    EventMessage.token(producer, "producer");
    EventMessage.token(eventType, "eventType");
    if (schemaVersion < 1) {
      throw new IllegalArgumentException("schemaVersion must be positive");
    }
  }

  /** Checks the explicitly trusted publisher and schema before business dispatch. */
  public void validate(EventMessage event) {
    if (!producer.equals(event.producer()) || !eventType.equals(event.eventType())
        || schemaVersion != event.schemaVersion()) {
      throw new IllegalArgumentException("Event does not match subscription contract");
    }
  }
}
