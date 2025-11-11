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
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.context.UserContext;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.RolePermissionsRelevanceDto;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.PermissionsRelevanceVo;
import org.simplepoint.plugin.rbac.core.api.service.PermissionsService;
import org.simplepoint.security.entity.Permissions;
import org.simplepoint.security.entity.RolePermissionsRelevance;
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
 * 处理权限相关请求的控制器
 * Controller handling requests related to permissions.
 */
@Slf4j
@RestController
@RequestMapping("/permissions")
@Tag(name = "权限管理", description = "用于管理系统中的权限")
public class PermissionsController extends BaseController<PermissionsService, Permissions, String> {

  /**
   * 用户上下文对象，用于获取当前用户信息
   * User context object used to retrieve current user information.
   */
  private final UserContext<BaseUser> userContext;

  /**
   * 构造函数，初始化权限服务和用户上下文
   * Constructor initializing the permissions service and user context.
   *
   * @param service     权限服务
   *                    The permissions service.
   * @param userContext 用户上下文
   *                    The user context.
   */
  public PermissionsController(
      final PermissionsService service,
      final UserContext<BaseUser> userContext
  ) {
    super(service);
    this.userContext = userContext;
  }

  /**
   * 获取权限的分页列表
   * Retrieves a paginated list of permissions.
   *
   * @param attributes 查询参数 Query parameters.
   * @param pageable   分页信息 Pagination details.
   * @return 权限的分页响应 Response containing paginated permissions.
   */
  @GetMapping
  @Operation(summary = "获取权限的分页列表", description = "根据查询参数和分页信息获取权限的分页列表")
  @PreAuthorize("hasAuthority('menu.permissions.view')")
  public Response<Page<Permissions>> limit(
      @RequestParam Map<String, String> attributes,
      Pageable pageable
  ) {
    log.info("current login username: {}", userContext.getDetails().toString());
    return limit(service.limit(attributes, pageable), Permissions.class);
  }

  /**
   * 添加新的权限
   * Adds a new permission.
   *
   * @param data 权限数据 Permission data.
   * @return 新增的权限响应 Response containing the newly added permission.
   * @throws Exception 可能的异常
   *                   Possible exceptions.
   */
  @PostMapping
  @Operation(summary = "添加新的权限", description = "添加一个新的权限到系统中")
  @PreAuthorize("hasAuthority('menu.permissions.add')")
  public Response<Permissions> add(@RequestBody Permissions data) throws Exception {
    return ok(service.add(data));
  }

  /**
   * 修改现有权限
   * Modifies an existing permission.
   *
   * @param data 权限数据  Permission data.
   * @return 修改后的权限响应  Response containing the modified permission.
   */
  @PutMapping
  @Operation(summary = "修改现有权限", description = "根据提供的数据修改一个现有的权限")
  @PreAuthorize("hasAuthority('menu.permissions.edit')")
  public Response<Permissions> modify(@RequestBody Permissions data) {
    return ok(service.modifyById(data));
  }

  /**
   * 删除指定的权限 ID
   * Removes permissions with specified IDs.
   *
   * @param ids 权限 ID 集合 Collection of permission IDs.
   * @return 删除操作的响应 Response confirming deletion.
   */
  @DeleteMapping
  @Operation(summary = "删除指定的权限 ID", description = "根据提供的权限 ID 集合删除对应的权限")
  @PreAuthorize("hasAuthority('menu.permissions.delete')")
  public Response<Set<String>> remove(@RequestParam("ids") String ids) {
    Set<String> idSet = StringUtil.stringToSet(ids);
    service.removeByIds(idSet);
    return ok(idSet);
  }

  /**
   * 获取权限下拉列表数据
   * Retrieves permission dropdown list data.
   *
   * @param pageable 分页信息 Pagination details.
   * @return 权限下拉列表数据响应 Response containing permission dropdown list data.
   */
  @GetMapping("/items")
  @Operation(summary = "获取权限下拉列表数据", description = "检索用于权限选择的权限下拉列表数据")
  @PreAuthorize("hasAuthority('menu.permissions.authorized') or hasPermission('menu.roles.unauthorized')")
  public Response<Page<PermissionsRelevanceVo>> items(Pageable pageable) {
    return ok(service.permissionItems(pageable));
  }
}


