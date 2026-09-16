/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.logging.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.auditing.logging.api.entity.ResourceGrantLog;
import org.simplepoint.plugin.auditing.logging.api.service.ResourceGrantLogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only resource grant log controller.
 */
@RestController
@RequestMapping("/logging/resource-grant-logs")
@Tag(name = "资源授权变更记录", description = "用于查询系统中的资源授权变更记录")
public class ResourceGrantLogController extends BaseController<ResourceGrantLogService, ResourceGrantLog, String> {

  /**
   * Creates the controller with the backing resource grant log service.
   *
   * @param service the resource grant log service
   */
  public ResourceGrantLogController(final ResourceGrantLogService service) {
    super(service);
  }

  /**
   * Queries resource grant logs with the provided filters and paging options.
   *
   * @param attributes filter attributes
   * @param pageable   paging information
   * @return paged resource grant logs
   */
  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('resource-grant-logs.view')")
  @Operation(summary = "分页查询资源授权变更记录", description = "根据提供的属性和分页参数，检索资源授权变更记录的分页列表")
  public Response<Page<ResourceGrantLog>> limit(@RequestParam Map<String, String> attributes, Pageable pageable) {
    return limit(service.limit(attributes, pageable), ResourceGrantLog.class);
  }
}
