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
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.rbac.core.api.service.DataScopeService;
import org.simplepoint.security.entity.DataScope;
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
 * REST controller for managing {@link DataScope} entities.
 */
@RestController
@RequestMapping("/data-scopes")
@Tag(name = "数据范围管理", description = "用于管理系统中的行级数据访问范围策略")
public class DataScopeController extends BaseController<DataScopeService, DataScope, String> {

  public DataScopeController(DataScopeService service) {
    super(service);
  }

  @GetMapping
  @Operation(summary = "分页查询数据范围", description = "根据提供的属性和分页参数检索数据范围列表")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('data-scope.view')")
  public Response<Page<DataScope>> limit(@RequestParam Map<String, String> attributes, Pageable pageable) {
    return limit(service.limit(attributes, pageable), DataScope.class);
  }

  @PostMapping
  @Operation(summary = "添加数据范围", description = "创建新的数据范围策略")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('data-scope.create')")
  public Response<DataScope> add(@RequestBody DataScope data) throws Exception {
    return ok(service.create(data));
  }

  @PutMapping
  @Operation(summary = "更新数据范围", description = "更新已有的数据范围策略")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('data-scope.edit')")
  public Response<DataScope> modify(@RequestBody DataScope data) {
    return ok(service.modifyById(data));
  }

  @DeleteMapping
  @Operation(summary = "删除数据范围", description = "根据 ID 删除一个或多个数据范围策略")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('data-scope.delete')")
  public Response<Set<String>> remove(@RequestParam("ids") String ids) {
    Set<String> idSet = StringUtil.stringToSet(ids);
    service.removeByIds(idSet);
    return ok(idSet);
  }
}
