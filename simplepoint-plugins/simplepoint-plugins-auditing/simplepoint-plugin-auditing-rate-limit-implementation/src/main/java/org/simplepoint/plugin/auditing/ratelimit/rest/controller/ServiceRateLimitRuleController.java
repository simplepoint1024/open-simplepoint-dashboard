/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.ratelimit.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.auditing.ratelimit.api.entity.ServiceRateLimitRule;
import org.simplepoint.plugin.auditing.ratelimit.api.service.ServiceRateLimitRuleService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for service-level rate-limit rule management.
 */
@RestController
@RequestMapping("/rate-limit/service-rules")
@Tag(name = "服务限流规则", description = "用于配置和管理网关的服务级限流规则")
public class ServiceRateLimitRuleController
    extends BaseController<ServiceRateLimitRuleService, ServiceRateLimitRule, String> {

  /**
   * Creates the controller with the backing service.
   *
   * @param service the service-rate-limit rule service
   */
  public ServiceRateLimitRuleController(final ServiceRateLimitRuleService service) {
    super(service);
  }

  /**
   * Queries service-level rate-limit rules with filters and paging options.
   *
   * @param attributes filter attributes
   * @param pageable   paging information
   * @return paged service-level rate-limit rules
   */
  @GetMapping
  @Operation(summary = "分页查询服务限流规则", description = "根据提供的属性和分页参数，检索服务限流规则的分页列表")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('service.rate.limit.rules.view')")
  public Response<Page<ServiceRateLimitRule>> limit(@RequestParam Map<String, String> attributes, Pageable pageable) {
    return limit(service.limit(attributes, pageable), ServiceRateLimitRule.class);
  }

  /**
   * Creates a new service-level rate-limit rule.
   *
   * @param data the rule to create
   * @return the created rule
   */
  @PostMapping
  @Operation(summary = "新增服务限流规则", description = "创建新的服务级限流规则")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('service.rate.limit.rules.create')")
  public Response<ServiceRateLimitRule> add(@RequestBody final ServiceRateLimitRule data) {
    return ok(service.create(data));
  }

  /**
   * Updates an existing service-level rate-limit rule.
   *
   * @param data the rule to update
   * @return the updated rule
   */
  @PutMapping
  @Operation(summary = "更新服务限流规则", description = "更新已有的服务级限流规则")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('service.rate.limit.rules.edit')")
  public Response<ServiceRateLimitRule> modify(@RequestBody final ServiceRateLimitRule data) {
    return ok(service.modifyById(data));
  }

  /**
   * Deletes one or more service-level rate-limit rules.
   *
   * @param ids comma-separated ids
   * @return deleted ids
   */
  @DeleteMapping
  @Operation(summary = "删除服务限流规则", description = "根据提供的规则ID删除一个或多个服务限流规则")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('service.rate.limit.rules.delete')")
  public Response<Set<String>> remove(@RequestParam("ids") final String ids) {
    Set<String> idSet = StringUtil.stringToSet(ids);
    service.removeByIds(idSet);
    return ok(idSet);
  }
}
