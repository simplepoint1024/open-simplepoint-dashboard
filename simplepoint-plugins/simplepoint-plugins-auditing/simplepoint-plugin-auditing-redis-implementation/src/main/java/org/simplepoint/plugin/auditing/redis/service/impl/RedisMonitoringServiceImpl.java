/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.redis.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.simplepoint.plugin.auditing.redis.api.model.RedisEntryDetail;
import org.simplepoint.plugin.auditing.redis.api.model.RedisEntrySummary;
import org.simplepoint.plugin.auditing.redis.api.model.RedisEntryType;
import org.simplepoint.plugin.auditing.redis.api.model.RedisValueUpsertCommand;
import org.simplepoint.plugin.auditing.redis.api.service.RedisMonitoringService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Service;

/**
 * Redis monitoring service implementation backed by the configured Redis instance.
 */
@Service
public class RedisMonitoringServiceImpl implements RedisMonitoringService {

  private static final int SCAN_COUNT = 500;
  private static final int SAMPLE_SIZE = 100;
  private static final int PREVIEW_LENGTH = 200;

  private final StringRedisTemplate stringRedisTemplate;
  private final ObjectMapper objectMapper;

  /**
   * Creates the service with the Redis template and JSON serializer.
   *
   * @param stringRedisTemplate the redis template
   * @param objectMapper        the object mapper
   */
  public RedisMonitoringServiceImpl(
      final StringRedisTemplate stringRedisTemplate,
      final ObjectMapper objectMapper
  ) {
    this.stringRedisTemplate = stringRedisTemplate;
    this.objectMapper = objectMapper;
  }

  @Override
  public Page<RedisEntrySummary> limit(final String pattern, final String type, final Pageable pageable) {
    RedisEntryType typeFilter = RedisEntryType.fromValue(type);
    List<String> matchedKeys = scanKeys(normalizePattern(pattern)).stream()
        .filter(key -> matchesType(key, typeFilter))
        .sorted(String.CASE_INSENSITIVE_ORDER)
        .collect(Collectors.toList());

    Pageable effectivePageable = pageable == null ? Pageable.unpaged() : pageable;
    if (effectivePageable.isUnpaged()) {
      List<RedisEntrySummary> content = matchedKeys.stream()
          .map(this::readSummary)
          .filter(Objects::nonNull)
          .collect(Collectors.toList());
      return new PageImpl<>(content);
    }

    int fromIndex = Math.toIntExact(Math.min(effectivePageable.getOffset(), matchedKeys.size()));
    int toIndex = Math.min(fromIndex + effectivePageable.getPageSize(), matchedKeys.size());
    List<RedisEntrySummary> content = matchedKeys.subList(fromIndex, toIndex).stream()
        .map(this::readSummary)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
    return new PageImpl<>(content, effectivePageable, matchedKeys.size());
  }

  @Override
  public RedisEntryDetail detail(final String key) {
    String normalizedKey = normalizeKey(key);
    RedisEntryType type = readType(normalizedKey);
    if (type == null) {
      throw new NoSuchElementException("Redis key does not exist: " + normalizedKey);
    }
    return readDetail(normalizedKey, type);
  }

  @Override
  public RedisEntryDetail create(final RedisValueUpsertCommand command) {
    RedisValueUpsertCommand normalized = normalizeCommand(command);
    if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(normalized.getKey()))) {
      throw new IllegalArgumentException("Redis key already exists: " + normalized.getKey());
    }
    writeStringValue(normalized);
    return detail(normalized.getKey());
  }

  @Override
  public RedisEntryDetail update(final RedisValueUpsertCommand command) {
    RedisValueUpsertCommand normalized = normalizeCommand(command);
    RedisEntryType type = readType(normalized.getKey());
    if (type == null) {
      throw new NoSuchElementException("Redis key does not exist: " + normalized.getKey());
    }
    if (type != RedisEntryType.STRING) {
      throw new IllegalArgumentException(
          "Only string Redis keys can be edited through key-value operations: " + normalized.getKey()
      );
    }
    writeStringValue(normalized);
    return detail(normalized.getKey());
  }

  @Override
  public void delete(final Collection<String> keys) {
    List<String> normalizedKeys = normalizeKeys(keys);
    if (normalizedKeys.isEmpty()) {
      throw new IllegalArgumentException("At least one redis key is required");
    }
    stringRedisTemplate.delete(normalizedKeys);
  }

  private RedisEntrySummary readSummary(final String key) {
    RedisEntryType type = readType(key);
    if (type == null) {
      return null;
    }
    Long rawTtl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
    return new RedisEntrySummary(
        key,
        type,
        normalizeTtl(rawTtl),
        isPersistent(rawTtl),
        readSize(key, type),
        truncate(readSerializedValue(key, type), PREVIEW_LENGTH),
        isEditable(type)
    );
  }

  private RedisEntryDetail readDetail(final String key, final RedisEntryType type) {
    Long rawTtl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
    return new RedisEntryDetail(
        key,
        type,
        normalizeTtl(rawTtl),
        isPersistent(rawTtl),
        readSize(key, type),
        isEditable(type),
        readSerializedValue(key, type)
    );
  }

  private boolean matchesType(final String key, final RedisEntryType typeFilter) {
    if (typeFilter == null) {
      return true;
    }
    return typeFilter == readType(key);
  }

  private RedisEntryType readType(final String key) {
    DataType dataType = stringRedisTemplate.execute(
        (RedisConnection connection) -> connection.type(serializeKey(key))
    );
    return dataType == null ? null : RedisEntryType.fromCode(dataType.code());
  }

  private Long readSize(final String key, final RedisEntryType type) {
    return switch (type) {
      case STRING -> stringRedisTemplate.opsForValue().size(key);
      case HASH -> stringRedisTemplate.opsForHash().size(key);
      case LIST -> stringRedisTemplate.opsForList().size(key);
      case SET -> stringRedisTemplate.opsForSet().size(key);
      case ZSET -> stringRedisTemplate.opsForZSet().size(key);
      case STREAM, UNKNOWN -> null;
    };
  }

  private String readSerializedValue(final String key, final RedisEntryType type) {
    return switch (type) {
      case STRING -> defaultString(stringRedisTemplate.opsForValue().get(key));
      case HASH -> toJson(readHashPayload(key));
      case LIST -> toJson(readListPayload(key));
      case SET -> toJson(readSetPayload(key));
      case ZSET -> toJson(readSortedSetPayload(key));
      case STREAM -> toJson(Map.of("message", "Redis stream preview is not supported yet"));
      case UNKNOWN -> toJson(Map.of("message", "Unsupported redis data type"));
    };
  }

  private Map<String, Object> readHashPayload(final String key) {
    Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);
    if (entries == null) {
      entries = Map.of();
    }
    Map<String, String> values = entries.entrySet().stream()
        .map(entry -> Map.entry(stringify(entry.getKey()), stringify(entry.getValue())))
        .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
        .limit(SAMPLE_SIZE)
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            Map.Entry::getValue,
            (left, right) -> left,
            LinkedHashMap::new
        ));
    return samplePayload(entries.size(), values);
  }

  private Map<String, Object> readListPayload(final String key) {
    List<String> values = defaultList(
        stringRedisTemplate.opsForList().range(key, 0, SAMPLE_SIZE - 1)
    )
        .stream()
        .map(this::stringify)
        .collect(Collectors.toList());
    Long size = stringRedisTemplate.opsForList().size(key);
    return samplePayload(size == null ? values.size() : size, values);
  }

  private Map<String, Object> readSetPayload(final String key) {
    Set<String> members = defaultSet(stringRedisTemplate.opsForSet().members(key));
    List<String> values = members.stream()
        .map(this::stringify)
        .sorted(String.CASE_INSENSITIVE_ORDER)
        .limit(SAMPLE_SIZE)
        .collect(Collectors.toList());
    return samplePayload(members.size(), values);
  }

  private Map<String, Object> readSortedSetPayload(final String key) {
    Set<TypedTuple<String>> tuples = defaultSet(stringRedisTemplate.opsForZSet().rangeWithScores(key, 0, SAMPLE_SIZE - 1));
    List<Map<String, Object>> values = tuples.stream()
        .map(tuple -> {
          Map<String, Object> item = new LinkedHashMap<>();
          item.put("value", tuple.getValue());
          item.put("score", tuple.getScore());
          return item;
        })
        .collect(Collectors.toList());
    Long size = stringRedisTemplate.opsForZSet().size(key);
    return samplePayload(size == null ? values.size() : size, values);
  }

  private Map<String, Object> samplePayload(final long size, final Object values) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("sampled", size > SAMPLE_SIZE);
    payload.put("size", size);
    payload.put("values", values);
    return payload;
  }

  private String toJson(final Object value) {
    try {
      return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Failed to serialize redis value payload", ex);
    }
  }

  private List<String> scanKeys(final String pattern) {
    List<String> keys = stringRedisTemplate.execute((RedisConnection connection) -> {
      LinkedHashSet<String> results = new LinkedHashSet<>();
      ScanOptions options = ScanOptions.scanOptions()
          .match(pattern)
          .count(SCAN_COUNT)
          .build();
      try (Cursor<byte[]> cursor = connection.scan(options)) {
        while (cursor.hasNext()) {
          String key = deserializeKey(cursor.next());
          if (key != null) {
            results.add(key);
          }
        }
      }
      return new ArrayList<>(results);
    });
    return keys == null ? List.of() : keys;
  }

  private void writeStringValue(final RedisValueUpsertCommand command) {
    if (Boolean.TRUE.equals(command.getPersistent())) {
      stringRedisTemplate.opsForValue().set(command.getKey(), command.getValue());
      return;
    }
    if (command.getTtlSeconds() == null || command.getTtlSeconds() <= 0) {
      throw new IllegalArgumentException("ttlSeconds must be greater than 0 when persistent is false");
    }
    stringRedisTemplate.opsForValue().set(
        command.getKey(),
        command.getValue(),
        command.getTtlSeconds(),
        TimeUnit.SECONDS
    );
  }

  private RedisValueUpsertCommand normalizeCommand(final RedisValueUpsertCommand command) {
    if (command == null) {
      throw new IllegalArgumentException("Redis command must not be null");
    }
    if (command.getValue() == null) {
      throw new IllegalArgumentException("Redis value must not be null");
    }
    return new RedisValueUpsertCommand(
        normalizeKey(command.getKey()),
        command.getValue(),
        command.getTtlSeconds(),
        Boolean.TRUE.equals(command.getPersistent())
    );
  }

  private String normalizeKey(final String key) {
    String normalized = trimToNull(key);
    if (normalized == null) {
      throw new IllegalArgumentException("Redis key must not be blank");
    }
    return normalized;
  }

  private List<String> normalizeKeys(final Collection<String> keys) {
    if (keys == null) {
      return List.of();
    }
    return keys.stream()
        .map(this::trimToNull)
        .filter(Objects::nonNull)
        .distinct()
        .collect(Collectors.toList());
  }

  private String normalizePattern(final String pattern) {
    String normalized = trimToNull(pattern);
    return normalized == null ? "*" : normalized;
  }

  private byte[] serializeKey(final String key) {
    byte[] bytes = stringRedisTemplate.getStringSerializer().serialize(key);
    if (bytes == null) {
      throw new IllegalArgumentException("Failed to serialize redis key: " + key);
    }
    return bytes;
  }

  private String deserializeKey(final byte[] value) {
    return stringRedisTemplate.getStringSerializer().deserialize(value);
  }

  private Long normalizeTtl(final Long ttlSeconds) {
    if (ttlSeconds == null || ttlSeconds < 0) {
      return null;
    }
    return ttlSeconds;
  }

  private Boolean isPersistent(final Long ttlSeconds) {
    return ttlSeconds != null && ttlSeconds == -1L;
  }

  private boolean isEditable(final RedisEntryType type) {
    return type == RedisEntryType.STRING;
  }

  private String truncate(final String value, final int length) {
    if (value == null || value.length() <= length) {
      return value;
    }
    return value.substring(0, length) + "...";
  }

  private String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private String defaultString(final String value) {
    return value == null ? "" : value;
  }

  private <T> Set<T> defaultSet(final Set<T> values) {
    return values == null ? Set.of() : values;
  }

  private <T> List<T> defaultList(final List<T> values) {
    return values == null ? List.of() : values;
  }

  private String stringify(final Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
