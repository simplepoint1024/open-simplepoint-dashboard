/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.UserPreferenceDto;
import org.simplepoint.plugin.rbac.core.api.service.UserPreferenceService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for managing per-user UI preferences.
 * Endpoints are scoped to the currently authenticated user.
 */
@RestController
@RequestMapping("/users/preferences")
@Tag(name = "用户偏好设置", description = "管理当前用户的 UI 偏好配置（如表格列显示、列宽等）")
public class UserPreferenceController {

  private final UserPreferenceService service;

  /**
   * Constructs a UserPreferenceController with the given service.
   *
   * @param service the UserPreferenceService
   */
  public UserPreferenceController(final UserPreferenceService service) {
    this.service = service;
  }

  /**
   * Retrieves a preference value for the current user.
   *
   * @param key the preference key
   * @return the preference value, or null if not set
   */
  @GetMapping("/{key}")
  @Operation(summary = "获取用户偏好", description = "获取当前用户指定 key 的偏好值")
  public Response<String> get(@PathVariable("key") String key) {
    return Response.okay(service.getPreference(key).orElse(null));
  }

  /**
   * Saves a preference value for the current user.
   *
   * @param key   the preference key
   * @param value the preference value (JSON text)
   * @return success response
   */
  @PutMapping("/{key}")
  @Operation(summary = "保存用户偏好", description = "保存当前用户指定 key 的偏好值")
  public Response<Void> set(@PathVariable("key") String key, @RequestBody UserPreferenceDto dto) {
    service.setPreference(key, dto.value());
    return Response.okay();
  }

  /**
   * Deletes a preference for the current user.
   *
   * @param key the preference key
   * @return success response
   */
  @DeleteMapping("/{key}")
  @Operation(summary = "删除用户偏好", description = "删除当前用户指定 key 的偏好值")
  public Response<Void> delete(@PathVariable("key") String key) {
    service.deletePreference(key);
    return Response.okay();
  }
}
