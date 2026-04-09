/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.redis.api.service;

import java.util.Collection;
import org.simplepoint.plugin.auditing.redis.api.model.RedisEntryDetail;
import org.simplepoint.plugin.auditing.redis.api.model.RedisEntrySummary;
import org.simplepoint.plugin.auditing.redis.api.model.RedisValueUpsertCommand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Service contract for Redis monitoring and key-value management.
 */
public interface RedisMonitoringService {

  /**
   * Queries Redis keys with optional pattern and type filters.
   *
   * @param pattern  the Redis glob pattern
   * @param type     the Redis type filter
   * @param pageable paging information
   * @return paged Redis entry summaries
   */
  Page<RedisEntrySummary> limit(String pattern, String type, Pageable pageable);

  /**
   * Loads a single Redis key detail.
   *
   * @param key the Redis key
   * @return the Redis key detail
   */
  RedisEntryDetail detail(String key);

  /**
   * Creates a new string Redis key.
   *
   * @param command the create command
   * @return the created Redis entry detail
   */
  RedisEntryDetail create(RedisValueUpsertCommand command);

  /**
   * Updates an existing string Redis key.
   *
   * @param command the update command
   * @return the updated Redis entry detail
   */
  RedisEntryDetail update(RedisValueUpsertCommand command);

  /**
   * Deletes one or more Redis keys.
   *
   * @param keys the Redis keys
   */
  void delete(Collection<String> keys);
}
