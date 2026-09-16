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
import org.simplepoint.plugin.auditing.logging.api.entity.LoginLog;
import org.simplepoint.plugin.auditing.logging.api.service.LoginLogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only login log controller.
 */
@RestController
@RequestMapping("/logging/login-logs")
@Tag(name = "登录日志", description = "用于查询系统中的登录日志")
public class LoginLogController extends BaseController<LoginLogService, LoginLog, String> {

  /**
   * Creates the controller with the backing login log service.
   *
   * @param service the login log service
   */
  public LoginLogController(final LoginLogService service) {
    super(service);
  }

  /**
   * Queries login logs with the provided filters and paging options.
   *
   * @param attributes filter attributes
   * @param pageable   paging information
   * @return paged login logs
   */
  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('login.logs.view')")
  @Operation(summary = "分页查询登录日志", description = "根据提供的属性和分页参数，检索登录日志的分页列表")
  public Response<Page<LoginLog>> limit(@RequestParam Map<String, String> attributes, Pageable pageable) {
    return limit(service.limit(attributes, pageable), LoginLog.class);
  }
}
