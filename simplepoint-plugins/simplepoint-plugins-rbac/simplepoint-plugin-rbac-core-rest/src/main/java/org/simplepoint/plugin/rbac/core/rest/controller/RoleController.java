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
import org.simplepoint.plugin.rbac.core.api.pojo.dto.RolePermissionsRelevanceDto;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RoleRelevanceVo;
import org.simplepoint.plugin.rbac.core.api.service.RoleService;
import org.simplepoint.security.entity.Role;
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
 * REST controller for managing Role entities in the RBAC (Role-Based Access Control) system.
 * This controller provides endpoints for retrieving, creating, updating, and deleting roles.
 */
@RestController
@RequestMapping("/roles")
@Tag(name = "角色管理", description = "用于管理系统中的角色")
public class RoleController extends BaseController<RoleService, Role, String> {

  /**
   * Constructs a RoleController with the specified service.
   *
   * @param service The RolesService instance to be used.
   */
  public RoleController(final RoleService service) {
    super(service);
  }

  /**
   * Retrieves a paginated list of roles based on the provided attributes and pageable parameters.
   *
   * @param attributes a map of attributes to filter the roles
   * @param pageable   the pagination and sorting information
   * @return a paginated response containing roles that match the given attributes
   */
  @GetMapping
  @Operation(summary = "分页查询角色", description = "根据提供的属性和分页参数，检索角色的分页列表")
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('roles.view')")
  public Response<Page<Role>> limit(@RequestParam Map<String, String> attributes, Pageable pageable) {
    return limit(service.limit(attributes, pageable), Role.class);
  }

  /**
   * Adds a new role to the system.
   *
   * @param data the Role object to be added
   * @return a response containing the added Role object
   * @throws Exception if an error occurs during creation
   */
  @PostMapping
  @Operation(summary = "添加新角色", description = "将新的角色添加到系统中")
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('roles.add')")
  public Response<Role> add(@RequestBody Role data) throws Exception {
    return ok(service.add(data));
  }

  /**
   * Updates an existing role in the system.
   *
   * @param data the Role object with updated information
   * @return a response containing the updated Role object
   */
  @PutMapping
  @Operation(summary = "更新角色信息", description = "更新系统中现有角色的信息")
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('roles.edit')")
  public Response<Role> modify(@RequestBody Role data) {
    return ok(service.modifyById(data));
  }

  /**
   * Deletes one or more roles identified by their IDs.
   *
   * @param ids a comma-separated string of role IDs to be deleted
   * @return a response indicating the success of the deletion operation
   */
  @DeleteMapping
  @Operation(summary = "删除角色", description = "根据提供的角色ID删除一个或多个角色")
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('roles.delete')")
  public Response<Set<String>> remove(@RequestParam("ids") String ids) {
    Set<String> idSet = StringUtil.stringToSet(ids);
    service.removeByIds(idSet);
    return ok(idSet);
  }

  /**
   * Retrieve a paginated list of RoleSelectDto for role selection purposes.
   *
   * @param pageable Pagination information.
   * @return A page of RoleSelectDto containing role selection data.
   */
  @GetMapping("/items")
  @Operation(summary = "获取角色下拉列表数据", description = "检索用于角色选择的角色下拉列表数据")
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('roles.config.permission') or hasAuthority('users.config.permission')")
  public Response<Page<RoleRelevanceVo>> items(Pageable pageable) {
    return ok(service.roleSelectItems(pageable));
  }

  /**
   * 获取角色权限
   * Retrieves authorized permissions for a given role.
   *
   * @param roleAuthority 角色权限标识 Role authority identifier.
   * @return 角色权限响应 Response containing authorized permissions.
   */
  @GetMapping("/authorized")
  @Operation(summary = "获取角色权限", description = "根据角色权限标识获取该用户")
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('roles.config.permission')")
  public Response<Collection<String>> authorized(@RequestParam("roleAuthority") String roleAuthority) {
    return ok(service.authorized(roleAuthority));
  }

  /**
   * 授权角色权限关联关系
   * Authorizes role-permission associations.
   *
   * @param dto 角色权限关联关系数据传输对象 Role-permission relevance data transfer object.
   * @return 授权操作的响应 Response containing authorized role-permission associations.
   */
  @PostMapping("/authorize")
  @Operation(summary = "授权角色权限关联关系", description = "根据一组角色权限关联关系进行授权")
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('roles.config.permission')")
  public Response<Collection<RolePermissionsRelevance>> authorize(@RequestBody RolePermissionsRelevanceDto dto) {
    return ok(service.authorize(dto));
  }

  /**
   * 授权角色权限关联关系
   * Authorizes role-permission associations.
   *
   * @param dto 角色权限关联关系数据传输对象
   * @return 操作响应
   */
  @PostMapping("/unauthorized")
  @Operation(summary = "取消授权角色权限关联关系", description = "根据角色权限关联关系取消授权")
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('roles.config.permission')")
  public Response<Void> unauthorized(@RequestBody RolePermissionsRelevanceDto dto) {
    service.unauthorized(dto.getRoleAuthority(), dto.getPermissionAuthorities());
    return Response.okay();
  }
}
