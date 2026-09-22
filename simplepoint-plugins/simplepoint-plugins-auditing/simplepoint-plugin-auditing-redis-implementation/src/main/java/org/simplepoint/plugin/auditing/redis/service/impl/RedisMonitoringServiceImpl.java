/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.redis.service.impl;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.simplepoint.plugin.auditing.redis.api.model.RedisEntryDetail;
import org.simplepoint.plugin.auditing.redis.api.model.RedisEntryType;
import org.simplepoint.plugin.auditing.redis.api.model.RedisValueUpsertCommand;
import org.simplepoint.plugin.auditing.redis.api.service.RedisMonitoringService;
import org.simplepoint.plugin.auditing.redis.service.query.RedisEntryFieldPolicy;
import org.simplepoint.plugin.auditing.redis.service.query.RedisEntryPreviewReader;
import org.simplepoint.plugin.auditing.redis.service.query.RedisEntryQueryService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Redis key management with shared field policy and bounded, on-demand value reads. */
@Service
public class RedisMonitoringServiceImpl implements RedisMonitoringService {
  private final StringRedisTemplate redis;
  private final RedisEntryPreviewReader previews;
  private final RedisEntryQueryService queries;

  /** Creates a manager sharing the platform preview and snapshot services. */
  public RedisMonitoringServiceImpl(
      StringRedisTemplate redis, RedisEntryPreviewReader previews, RedisEntryQueryService queries) {
    this.redis = redis;
    this.previews = previews;
    this.queries = queries;
  }

  @Override
  public RedisEntryDetail detail(String key) {
    RedisEntryFieldPolicy.requireIdentity();
    RedisEntryDetail detail =
        previews.read(normalizeKey(key), RedisEntryFieldPolicy.readable("value"));
    detail.setEditable(
        Boolean.TRUE.equals(detail.getEditable()) && RedisEntryFieldPolicy.canEdit());
    return detail;
  }

  @Override
  public RedisEntryDetail create(RedisValueUpsertCommand command) {
    RedisEntryFieldPolicy.requireWrite(true);
    RedisValueUpsertCommand normalized = normalizeCommand(command);
    if (Boolean.TRUE.equals(redis.hasKey(normalized.getKey()))) {
      throw new IllegalArgumentException("Redis key already exists");
    }
    writeStringValue(normalized);
    queries.invalidate();
    return detail(normalized.getKey());
  }

  @Override
  public RedisEntryDetail update(RedisValueUpsertCommand command) {
    RedisEntryFieldPolicy.requireWrite(false);
    RedisValueUpsertCommand normalized = normalizeCommand(command);
    RedisEntryDetail current = detail(normalized.getKey());
    if (current.getType() != RedisEntryType.STRING) {
      throw new IllegalArgumentException("Only string Redis keys can be edited");
    }
    if (!Boolean.TRUE.equals(current.getEditable())) {
      throw new IllegalArgumentException(
          "Only complete UTF-8 string values within the editing limit can be edited");
    }
    writeStringValue(normalized);
    queries.invalidate();
    return detail(normalized.getKey());
  }

  @Override
  public void delete(Collection<String> keys) {
    RedisEntryFieldPolicy.requireIdentity();
    if (keys == null || keys.isEmpty() || keys.size() > 100) {
      throw new IllegalArgumentException("At least one and at most 100 redis keys are required");
    }
    List<String> normalized = keys.stream().map(this::normalizeKey).distinct().toList();
    redis.delete(normalized);
    queries.invalidate();
  }

  private void writeStringValue(RedisValueUpsertCommand command) {
    if (Boolean.TRUE.equals(command.getPersistent())) {
      redis.opsForValue().set(command.getKey(), command.getValue());
    } else {
      redis
          .opsForValue()
          .set(command.getKey(), command.getValue(), command.getTtlSeconds(), TimeUnit.SECONDS);
    }
  }

  private RedisValueUpsertCommand normalizeCommand(RedisValueUpsertCommand command) {
    if (command == null) {
      throw new IllegalArgumentException("Redis command must not be null");
    }
    if (command.getValue() == null) {
      throw new IllegalArgumentException("Redis value must not be null");
    }
    final String key = normalizeKey(command.getKey());
    byte[] value = command.getValue().getBytes(StandardCharsets.UTF_8);
    if (!command.getValue().equals(new String(value, StandardCharsets.UTF_8))) {
      throw new IllegalArgumentException("Redis value is not valid UTF-8");
    }
    if (value.length > RedisEntryPreviewReader.MAX_VALUE_BYTES) {
      throw new IllegalArgumentException(
          "Redis value exceeds the editing limit of 262144 UTF-8 bytes");
    }
    if (!Boolean.TRUE.equals(command.getPersistent())
        && (command.getTtlSeconds() == null || command.getTtlSeconds() <= 0)) {
      throw new IllegalArgumentException(
          "ttlSeconds must be greater than 0 when persistent is false");
    }
    return new RedisValueUpsertCommand(
        key,
        command.getValue(),
        command.getTtlSeconds(),
        Boolean.TRUE.equals(command.getPersistent()));
  }

  private String normalizeKey(String key) {
    if (key == null || key.isEmpty()) {
      throw new IllegalArgumentException("Redis key must not be blank");
    }
    byte[] encoded = key.getBytes(StandardCharsets.UTF_8);
    if (!key.equals(new String(encoded, StandardCharsets.UTF_8))) {
      throw new IllegalArgumentException("Redis key is not valid UTF-8");
    }
    return key;
  }
}
