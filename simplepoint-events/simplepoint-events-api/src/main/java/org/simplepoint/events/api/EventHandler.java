/*
 * Copyright (c) 2026 SimplePoint
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.events.api;

/** Handles a durable inbox entry in its local transaction; use outbox for remote effects. */
public interface EventHandler {
  /** One stable subscription ID for every independent business effect. */
  EventSubscription subscription();

  /** All database effects must use the event store's transaction manager and data source. */
  void handle(EventMessage event);
}
