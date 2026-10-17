/*
 * Copyright (c) 2026 SimplePoint
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.events.rabbit;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit opt-in, identity and operational bounds for the platform event infrastructure. */
@Getter
@Setter
@ConfigurationProperties("simplepoint.events")
public class EventInfrastructureProperties {
  private String service;
  private boolean declareTopology;
  private Duration confirmTimeout = Duration.ofSeconds(10);
  private Duration dispatchDelay = Duration.ofSeconds(1);
  private int batchSize = 20;
  private int maxAttempts = 12;
  private Duration lease = Duration.ofSeconds(60);
  private Duration initialRetryDelay = Duration.ofSeconds(2);
  private Duration maxRetryDelay = Duration.ofMinutes(5);

}
