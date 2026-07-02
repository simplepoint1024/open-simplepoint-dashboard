/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.spring.coordination;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.simplepoint.plugin.spring.configuration.PluginRuntimeEventProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;

/**
 * Relays JDBC plugin operation events from other nodes to the local Spring event bus.
 */
public final class JdbcPluginOperationEventRelay implements SmartLifecycle {

  private static final Logger LOGGER = LoggerFactory.getLogger(JdbcPluginOperationEventRelay.class);

  private final JdbcPluginOperationEventStore eventStore;
  private final ApplicationEventPublisher eventPublisher;
  private final String originId;
  private final Duration pollInterval;
  private final int batchSize;
  private final Object monitor = new Object();
  private volatile boolean running;
  private Thread worker;
  private Instant cursorOccurredAt;
  private String cursorEventId = "";

  /**
   * Creates a JDBC plugin operation event relay.
   *
   * @param eventStore     JDBC operation event store
   * @param eventPublisher Spring application event publisher
   * @param properties     runtime event properties
   */
  public JdbcPluginOperationEventRelay(
      JdbcPluginOperationEventStore eventStore,
      ApplicationEventPublisher eventPublisher,
      PluginRuntimeEventProperties properties
  ) {
    this(
        eventStore,
        eventPublisher,
        properties.getJdbc().getOriginId(),
        properties.getJdbc().getPollInterval(),
        properties.getJdbc().getBatchSize(),
        properties.getJdbc().isReplayExisting(),
        Clock.systemUTC());
  }

  /**
   * Creates a JDBC plugin operation event relay.
   *
   * @param eventStore      JDBC operation event store
   * @param eventPublisher  Spring application event publisher
   * @param originId        event origin id
   * @param pollInterval    relay polling interval
   * @param batchSize       maximum events per poll
   * @param replayExisting  whether to replay existing events on startup
   * @param clock           clock
   */
  public JdbcPluginOperationEventRelay(
      JdbcPluginOperationEventStore eventStore,
      ApplicationEventPublisher eventPublisher,
      String originId,
      Duration pollInterval,
      int batchSize,
      boolean replayExisting,
      Clock clock
  ) {
    this.eventStore = eventStore;
    this.eventPublisher = eventPublisher;
    this.originId = requireText(originId, "originId");
    this.pollInterval = requirePositive(pollInterval, "pollInterval");
    this.batchSize = requirePositive(batchSize, "batchSize");
    Clock effectiveClock = clock == null ? Clock.systemUTC() : clock;
    this.cursorOccurredAt = replayExisting ? Instant.EPOCH : effectiveClock.instant();
  }

  @Override
  public void start() {
    synchronized (monitor) {
      if (running) {
        return;
      }
      running = true;
      worker = new Thread(this::run, "plugin-runtime-event-relay");
      worker.setDaemon(true);
      worker.start();
    }
  }

  @Override
  public void stop() {
    Thread threadToStop;
    synchronized (monitor) {
      running = false;
      threadToStop = worker;
      worker = null;
    }
    if (threadToStop != null) {
      threadToStop.interrupt();
    }
  }

  @Override
  public void stop(Runnable callback) {
    stop();
    callback.run();
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public boolean isAutoStartup() {
    return true;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }

  int pollOnce() {
    int published = 0;
    for (JdbcPluginStoredOperationEvent storedEvent :
        eventStore.listAfter(cursorOccurredAt, cursorEventId, originId, batchSize)) {
      eventPublisher.publishEvent(storedEvent.event());
      cursorOccurredAt = storedEvent.event().occurredAt();
      cursorEventId = storedEvent.id();
      published++;
    }
    return published;
  }

  private void run() {
    while (running) {
      try {
        pollOnce();
      } catch (RuntimeException runtimeException) {
        LOGGER.warn("Failed to relay plugin runtime events", runtimeException);
      }
      sleep();
    }
  }

  private void sleep() {
    try {
      Thread.sleep(Math.max(1, pollInterval.toMillis()));
    } catch (InterruptedException interruptedException) {
      Thread.currentThread().interrupt();
    }
  }

  private static Duration requirePositive(Duration duration, String name) {
    if (duration == null || duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException(name + " must be greater than zero");
    }
    return duration;
  }

  private static int requirePositive(int value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be greater than zero");
    }
    return value;
  }

  private static String requireText(String value, String name) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value.trim();
  }
}
