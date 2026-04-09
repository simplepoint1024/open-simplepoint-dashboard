/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.ratelimit.gateway.support;

import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Mono;

/**
 * Delegates token-bucket enforcement to Spring Cloud Gateway's standard RedisRateLimiter.
 */
public class GatewayRedisRateLimiterSupport {

  private final ApplicationContext applicationContext;

  /**
   * Creates the limiter support with the current application context.
   *
   * @param applicationContext the application context
   */
  public GatewayRedisRateLimiterSupport(final ApplicationContext applicationContext) {
    this.applicationContext = applicationContext;
  }

  /**
   * Determines whether the request is allowed under the supplied rule settings.
   *
   * @param ruleId          the stable rule identifier
   * @param key             the derived rate-limit key
   * @param replenishRate   tokens replenished per second
   * @param burstCapacity   token-bucket burst capacity
   * @param requestedTokens tokens consumed per request
   * @return the rate-limit decision
   */
  public Mono<Response> isAllowed(
      final String ruleId,
      final String key,
      final int replenishRate,
      final long burstCapacity,
      final int requestedTokens
  ) {
    RedisRateLimiter redisRateLimiter = new RedisRateLimiter(replenishRate, burstCapacity, requestedTokens);
    redisRateLimiter.setApplicationContext(applicationContext);
    return redisRateLimiter.isAllowed(ruleId, key);
  }
}
