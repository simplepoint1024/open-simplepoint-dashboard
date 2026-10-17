/*
 * Copyright (c) 2026 SimplePoint
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.events.rabbit;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.simplepoint.events.jdbc.EventDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/** Separate bounded workers prevent broker unavailability from starving local event processing. */
public final class EventWorkers implements SmartLifecycle {
  private static final Logger LOG = LoggerFactory.getLogger(EventWorkers.class);
  private final EventDispatcher dispatcher;
  private final EventInfrastructureProperties properties;
  private ScheduledExecutorService executor;

  /** Creates independent bounded dispatch loops. */
  public EventWorkers(EventDispatcher dispatcher, EventInfrastructureProperties properties) {
    this.dispatcher = dispatcher;
    this.properties = properties;
  }

  @Override
  public synchronized void start() {
    if (executor != null) {
      return;
    }
    executor = Executors.newScheduledThreadPool(2, Thread.ofPlatform()
        .name("platform-events-", 0).daemon().factory());
    for (String direction : new String[]{"INBOX", "OUTBOX"}) {
      executor.scheduleWithFixedDelay(() -> {
        try {
          dispatcher.dispatch(direction, properties.getBatchSize());
        } catch (RuntimeException ex) {
          LOG.warn("Event worker {} deferred ({})", direction, ex.getClass().getSimpleName());
        }
      }, 0, properties.getDispatchDelay().toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  @Override
  public synchronized void stop() {
    if (executor != null) {
      executor.shutdown();
      try {
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
          executor.shutdownNow();
        }
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        executor.shutdownNow();
      }
      executor = null;
    }
  }

  @Override
  public synchronized boolean isRunning() {
    return executor != null;
  }
}
