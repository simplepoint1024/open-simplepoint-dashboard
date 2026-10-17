/*
 * Copyright (c) 2026 SimplePoint
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.events.api;

/** Appends to the local outbox in the caller's database transaction. */
@FunctionalInterface
public interface EventPublisher {
  /** Fails if no writable transaction is active. An event ID may only describe one fact. */
  void publish(EventMessage event);
}
