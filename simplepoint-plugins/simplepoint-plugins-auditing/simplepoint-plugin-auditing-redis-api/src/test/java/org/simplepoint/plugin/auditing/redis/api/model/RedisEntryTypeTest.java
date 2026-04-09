/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.redis.api.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RedisEntryTypeTest {

  @Test
  void shouldResolveUserProvidedType() {
    assertEquals(RedisEntryType.STRING, RedisEntryType.fromValue("string"));
    assertEquals(RedisEntryType.ZSET, RedisEntryType.fromValue("ZSET"));
    assertNull(RedisEntryType.fromValue("  "));
  }

  @Test
  void shouldResolveRedisNativeTypeCode() {
    assertEquals(RedisEntryType.HASH, RedisEntryType.fromCode("hash"));
    assertEquals(RedisEntryType.STREAM, RedisEntryType.fromCode("stream"));
    assertEquals(RedisEntryType.UNKNOWN, RedisEntryType.fromCode("vector"));
    assertNull(RedisEntryType.fromCode("none"));
  }

  @Test
  void shouldRejectUnsupportedUserFilter() {
    assertThrows(IllegalArgumentException.class, () -> RedisEntryType.fromValue("vector"));
  }
}
