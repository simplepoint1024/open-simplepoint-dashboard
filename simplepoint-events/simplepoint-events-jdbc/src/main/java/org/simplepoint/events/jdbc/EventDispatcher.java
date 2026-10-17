/*
 * Copyright (c) 2026 SimplePoint
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.events.jdbc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.events.api.EventHandler;
import org.simplepoint.events.api.EventTransport;

/** Bounded dispatcher; process owners can run independent instances against the same database. */
public final class EventDispatcher {
  private final JdbcEventStore store;
  private final EventTransport transport;
  private final Map<String, EventHandler> handlers = new HashMap<>();

  /** Builds an immutable registry and rejects ambiguous subscription ownership. */
  public EventDispatcher(JdbcEventStore store, EventTransport transport, List<EventHandler> handlers) {
    this.store = store;
    this.transport = transport;
    for (EventHandler handler : handlers) {
      if (this.handlers.putIfAbsent(handler.subscription().id(), handler) != null) {
        throw new IllegalArgumentException("Duplicate event subscription");
      }
    }
  }

  /** Executes a bounded batch. Outbox and inbox should run on separate scheduled workers. */
  public int dispatch(String direction, int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("Invalid dispatch batch size");
    }
    int processed = 0;
    while (processed < limit) {
      EventJob job = store.claim(direction);
      if (job == null) {
        break;
      }
      try {
        if ("OUTBOX".equals(direction)) {
          transport.send(job.event());
          store.sent(job);
        } else {
          store.process(job, () -> {
            EventHandler handler = handlers.get(job.subscription());
            if (handler == null) {
              throw new IllegalStateException("Subscription handler unavailable");
            }
            handler.subscription().validate(job.event());
            handler.handle(job.event());
          });
        }
      } catch (RuntimeException ex) {
        store.failed(job, ex);
      }
      processed++;
    }
    return processed;
  }
}
