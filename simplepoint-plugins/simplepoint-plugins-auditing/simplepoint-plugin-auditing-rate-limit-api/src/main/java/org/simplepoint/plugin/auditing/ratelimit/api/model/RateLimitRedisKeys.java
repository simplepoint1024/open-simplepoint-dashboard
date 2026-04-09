/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.ratelimit.api.model;

/**
 * Shared Redis keys used by the auditing service and gateway limiter.
 */
public final class RateLimitRedisKeys {

  public static final String SERVICE_RULES_KEY = "simplepoint:gateway:rate-limit:service-rules";
  public static final String ENDPOINT_RULES_KEY = "simplepoint:gateway:rate-limit:endpoint-rules";

  private RateLimitRedisKeys() {
  }
}
