/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.ratelimit.gateway.properties;

import lombok.Data;
import org.simplepoint.plugin.auditing.ratelimit.api.model.RateLimitRedisKeys;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Properties for the gateway rate-limit auto-configuration.
 */
@Data
@ConfigurationProperties(prefix = GatewayRateLimitProperties.PREFIX)
public class GatewayRateLimitProperties {

  public static final String PREFIX = "simplepoint.gateway.rate-limit";

  private boolean enabled = true;
  private String serviceRulesKey = RateLimitRedisKeys.SERVICE_RULES_KEY;
  private String endpointRulesKey = RateLimitRedisKeys.ENDPOINT_RULES_KEY;
  private long cacheSeconds = 5;
}
