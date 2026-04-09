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
import org.simplepoint.plugin.auditing.logging.api.entity.ErrorLog;
import org.simplepoint.plugin.auditing.logging.api.service.ErrorLogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only error log controller.
 */
@RestController
@RequestMapping("/logging/error-logs")
@Tag(name = "错误日志", description = "用于查询系统中的告警、错误和异常日志")
public class ErrorLogController extends BaseController<ErrorLogService, ErrorLog, String> {

  /**
   * Creates the controller with the backing error log service.
   *
   * @param service the error log service
   */
  public ErrorLogController(final ErrorLogService service) {
    super(service);
  }

  /**
   * Queries error logs with the provided filters and paging options.
   *
   * @param attributes filter attributes
   * @param pageable   paging information
   * @return paged error logs
   */
  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('error.logs.view')")
  @Operation(summary = "分页查询错误日志", description = "根据提供的属性和分页参数，检索错误日志的分页列表")
  public Response<Page<ErrorLog>> limit(@RequestParam Map<String, String> attributes, Pageable pageable) {
    return limit(service.limit(attributes, pageable), ErrorLog.class);
  }
}
