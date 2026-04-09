/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.redis.api.model;

import java.util.Arrays;

/**
 * Redis entry types supported by the monitoring module.
 */
public enum RedisEntryType {
  STRING,
  HASH,
  LIST,
  SET,
  ZSET,
  STREAM,
  UNKNOWN;

  /**
   * Resolves a user-provided type filter.
   *
   * @param value the user-provided value
   * @return the resolved type, or {@code null} when blank
   */
  public static RedisEntryType fromValue(final String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim().toUpperCase();
    return Arrays.stream(values())
        .filter(type -> type.name().equals(normalized))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unsupported redis type filter: " + value));
  }

  /**
   * Resolves a Redis native type code.
   *
   * @param code the redis type code
   * @return the resolved type, {@code null} for {@code none}, or {@link #UNKNOWN}
   */
  public static RedisEntryType fromCode(final String code) {
    if (code == null || code.isBlank()) {
      return null;
    }
    String normalized = code.trim().toUpperCase();
    if ("NONE".equals(normalized)) {
      return null;
    }
    return Arrays.stream(values())
        .filter(type -> type.name().equals(normalized))
        .findFirst()
        .orElse(UNKNOWN);
  }
}
