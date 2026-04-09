/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.ratelimit.api.model;

/**
 * Supported gateway rate-limit key strategies.
 */
public enum RateLimitKeyStrategy {
  GLOBAL,
  CLIENT_IP,
  USER_ID,
  TENANT_ID;

  /**
   * Normalizes a raw string value into a supported strategy name.
   *
   * @param value the raw value
   * @return the normalized strategy name
   */
  public static String normalize(final String value) {
    if (value == null || value.isBlank()) {
      return CLIENT_IP.name();
    }
    return RateLimitKeyStrategy.valueOf(value.trim().toUpperCase()).name();
  }
}
