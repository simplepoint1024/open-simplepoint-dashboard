/*
 * Copyright (c) 2026 SimplePoint
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.events.rabbit;

import java.util.List;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.SmartLifecycle;

/** Owns listener lifecycles even when the subscription list is empty. */
public final class EventListenerGroup implements SmartLifecycle {
  private final List<SimpleMessageListenerContainer> containers;
  private volatile boolean running;

  /** Takes ownership of the subscription containers. */
  public EventListenerGroup(List<SimpleMessageListenerContainer> containers) {
    this.containers = List.copyOf(containers);
  }

  @Override
  public void start() {
    containers.forEach(SimpleMessageListenerContainer::start);
    running = true;
  }

  @Override
  public void stop() {
    containers.forEach(SimpleMessageListenerContainer::stop);
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
