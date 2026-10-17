/*
 * Copyright (c) 2026 SimplePoint
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.events.api;

/** Transport SPI. Success confirms routing to durable broker storage, not business completion. */
@FunctionalInterface
public interface EventTransport {
  /** May deliver more than once if confirmation is lost; must preserve the event identity. */
  void send(EventMessage event);
}
