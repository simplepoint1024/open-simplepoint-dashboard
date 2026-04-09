/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.ratelimit.api.model;

/**
 * Common contract for gateway rate-limit rule definitions stored in Redis.
 */
public interface RateLimitRuleDefinition {

  /**
   * Returns the stable rule identifier.
   *
   * @return the rule identifier
   */
  String getId();

  /**
   * Returns the key strategy used to derive the rate-limit key.
   *
   * @return the key strategy
   */
  String getKeyStrategy();

  /**
   * Returns the replenish rate in tokens per second.
   *
   * @return the replenish rate
   */
  Integer getReplenishRate();

  /**
   * Returns the burst capacity.
   *
   * @return the burst capacity
   */
  Long getBurstCapacity();

  /**
   * Returns the requested tokens per request.
   *
   * @return the requested tokens
   */
  Integer getRequestedTokens();

  /**
   * Returns whether the rule is enabled.
   *
   * @return whether the rule is enabled
   */
  Boolean getEnabled();
}
