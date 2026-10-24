/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.redis.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.NoSuchElementException;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.auditing.redis.api.model.RedisValueUpsertCommand;
import org.simplepoint.plugin.auditing.redis.api.service.RedisMonitoringService;
import org.simplepoint.plugin.auditing.redis.service.query.RedisEntryFieldPolicy;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST controller for Redis monitoring and key-value operations. */
@RestController
@RequestMapping("/redis/entries")
@Tag(name = "Redis 管理", description = "用于浏览当前 Redis 中的键值并执行字符串键值操作")
public class RedisMonitoringController {

  private final RedisMonitoringService redisMonitoringService;

  /**
   * Creates the controller with the backing Redis monitoring service.
   *
   * @param redisMonitoringService the Redis monitoring service
   */
  public RedisMonitoringController(final RedisMonitoringService redisMonitoringService) {
    this.redisMonitoringService = redisMonitoringService;
  }

  /**
   * Loads a Redis key detail.
   *
   * @param key the Redis key
   * @return the Redis key detail
   */
  @GetMapping("/detail")
  @Operation(summary = "获取 Redis 键详情", description = "根据键名获取 Redis 键的详细内容")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('redis.entries.view')")
  public Response<?> detail(@RequestParam("key") final String key) {
    try {
      return Response.okay(RedisEntryFieldPolicy.project(redisMonitoringService.detail(key)));
    } catch (NoSuchElementException ex) {
      return notFound(ex.getMessage());
    }
  }

  /**
   * Creates a string Redis key.
   *
   * @param command the create command
   * @return the created Redis entry detail
   */
  @PostMapping
  @Operation(summary = "新增 Redis 字符串键", description = "新增一个字符串类型的 Redis 键值对")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('redis.entries.create')")
  public Response<?> add(@RequestBody final RedisValueUpsertCommand command) {
    try {
      return Response.okay(RedisEntryFieldPolicy.project(redisMonitoringService.create(command)));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Updates an existing string Redis key.
   *
   * @param command the update command
   * @return the updated Redis entry detail
   */
  @PutMapping
  @Operation(summary = "更新 Redis 字符串键", description = "更新一个已存在的字符串类型 Redis 键值对")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('redis.entries.edit')")
  public Response<?> modify(@RequestBody final RedisValueUpsertCommand command) {
    try {
      return Response.okay(RedisEntryFieldPolicy.project(redisMonitoringService.update(command)));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    } catch (NoSuchElementException ex) {
      return notFound(ex.getMessage());
    }
  }

  /**
   * Deletes one or more Redis keys.
   *
   * @param parameters repeated keys parameters, preserved without comma splitting or trimming
   * @return deleted keys
   */
  @DeleteMapping
  @Operation(summary = "删除 Redis 键", description = "根据给定键名删除一个或多个 Redis 键")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('redis.entries.delete')")
  public Response<?> remove(@RequestParam final MultiValueMap<String, String> parameters) {
    List<String> keys = parameters.get("keys");
    try {
      redisMonitoringService.delete(keys);
      return Response.okay(keys);
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  private Response<String> badRequest(final String message) {
    return Response.of(ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body(message));
  }

  private Response<String> notFound(final String message) {
    return Response.of(ResponseEntity.status(404).contentType(MediaType.TEXT_PLAIN).body(message));
  }
}
