/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.redis.api.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Command for creating or updating a string Redis key.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RedisValueUpsertCommand {
  private String key;
  private String value;
  private Long ttlSeconds;
  private Boolean persistent;
}
