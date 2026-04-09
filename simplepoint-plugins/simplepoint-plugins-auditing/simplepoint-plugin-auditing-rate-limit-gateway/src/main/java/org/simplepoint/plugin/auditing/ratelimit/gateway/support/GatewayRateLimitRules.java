/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.ratelimit.gateway.support;

import java.util.List;
import org.simplepoint.plugin.auditing.ratelimit.api.model.EndpointRateLimitRuleDefinition;
import org.simplepoint.plugin.auditing.ratelimit.api.model.ServiceRateLimitRuleDefinition;

/**
 * Immutable snapshot of the gateway rate-limit rules currently loaded from Redis.
 */
public record GatewayRateLimitRules(
    List<ServiceRateLimitRuleDefinition> serviceRules,
    List<EndpointRateLimitRuleDefinition> endpointRules
) {

  /**
   * Returns an empty rule snapshot.
   *
   * @return an empty rule snapshot
   */
  public static GatewayRateLimitRules empty() {
    return new GatewayRateLimitRules(List.of(), List.of());
  }
}
