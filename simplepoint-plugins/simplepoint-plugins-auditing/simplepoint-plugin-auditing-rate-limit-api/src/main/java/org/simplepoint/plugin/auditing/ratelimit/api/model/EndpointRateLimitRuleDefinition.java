/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.ratelimit.api.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Redis-published endpoint-level rate-limit rule definition.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EndpointRateLimitRuleDefinition implements RateLimitRuleDefinition {
  private String id;
  private String name;
  private String serviceId;
  private String pathPattern;
  private String httpMethod;
  private Integer sort;
  private String keyStrategy;
  private Integer replenishRate;
  private Long burstCapacity;
  private Integer requestedTokens;
  private Boolean enabled;
  private String description;
}
