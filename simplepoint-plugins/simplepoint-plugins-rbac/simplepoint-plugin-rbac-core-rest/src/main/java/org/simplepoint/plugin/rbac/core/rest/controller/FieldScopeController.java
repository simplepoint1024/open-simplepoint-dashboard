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
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.rbac.core.api.service.FieldScopeService;
import org.simplepoint.security.entity.FieldScope;
import org.simplepoint.security.entity.FieldScopeEntry;
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
 * REST controller for managing {@link FieldScope} entities.
 */
@RestController
@RequestMapping("/field-scopes")
@Tag(name = "字段范围管理", description = "用于管理系统中的列级字段访问权限策略")
public class FieldScopeController extends BaseController<FieldScopeService, FieldScope, String> {

  public FieldScopeController(FieldScopeService service) {
    super(service);
  }

  @GetMapping
  @Operation(summary = "分页查询字段范围", description = "根据提供的属性和分页参数检索字段范围列表")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('field-scope.view')")
  public Response<Page<FieldScope>> limit(@RequestParam Map<String, String> attributes, Pageable pageable) {
    return limit(service.limit(attributes, pageable), FieldScope.class);
  }

  @PostMapping
  @Operation(summary = "添加字段范围", description = "创建新的字段级访问权限策略")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('field-scope.create')")
  public Response<FieldScope> add(@RequestBody FieldScope data) throws Exception {
    return ok(service.create(data));
  }

  @PutMapping
  @Operation(summary = "更新字段范围", description = "更新已有的字段级访问权限策略")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('field-scope.edit')")
  public Response<FieldScope> modify(@RequestBody FieldScope data) {
    return ok(service.modifyById(data));
  }

  @DeleteMapping
  @Operation(summary = "删除字段范围", description = "根据 ID 删除一个或多个字段范围策略")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('field-scope.delete')")
  public Response<Set<String>> remove(@RequestParam("ids") String ids) {
    Set<String> idSet = StringUtil.stringToSet(ids);
    service.removeByIds(idSet);
    return ok(idSet);
  }

  @PutMapping("/entries")
  @Operation(summary = "替换字段范围条目", description = "将指定字段范围的所有条目替换为提供的列表")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('field-scope.edit')")
  public Response<FieldScope> replaceEntries(
      @RequestParam("fieldScopeId") String fieldScopeId,
      @RequestBody Collection<FieldScopeEntry> entries) {
    return ok(service.replaceEntries(fieldScopeId, entries));
  }
}
