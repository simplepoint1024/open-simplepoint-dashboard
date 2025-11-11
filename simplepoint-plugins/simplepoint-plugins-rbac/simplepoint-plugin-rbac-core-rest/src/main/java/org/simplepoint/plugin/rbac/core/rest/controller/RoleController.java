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
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.constants.Icons;
import org.simplepoint.core.constants.PublicButtonKeys;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.RoleSelectDto;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RoleSelectVo;
import org.simplepoint.plugin.rbac.core.api.service.RoleService;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.UserRoleRelevance;
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
  @PreAuthorize("hasAuthority('menu.roles.view')")
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
  @PreAuthorize("hasAuthority('menu.roles.add')")
  @ButtonDeclaration(
      title = PublicButtonKeys.ADD_TITLE,
      key = PublicButtonKeys.ADD_KEY,
      icon = Icons.PLUS_CIRCLE,
      sort = 0,
      argumentMaxSize = 1,
      argumentMinSize = 0,
      authority = "menu.roles.add"
  )
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
  @PreAuthorize("hasAuthority('menu.roles.edit')")
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
  @PreAuthorize("hasAuthority('menu.roles.delete')")
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
  @PreAuthorize("hasAuthority('menu.roles.authorized') or hasPermission('menu.roles.unauthorized')")
  public Response<Page<RoleSelectVo>> items(Pageable pageable) {
    return ok(service.roleSelectItems(pageable));
  }

  /**
   * Retrieves a collection of role authorities associated with a specific username.
   *
   * @param username The username to filter the role authorities.
   * @return A response containing a collection of role authorities for the given username.
   */
  @GetMapping("/authorized")
  @Operation(summary = "获取用户角色权限", description = "根据用户名获取该用户")
  @PreAuthorize("hasAuthority('menu.roles.authorize') or hasPermission('menu.roles.unauthorized')")
  public Response<Collection<String>> authorized(@RequestParam("username") String username) {
    return ok(service.userRoleAuthorities(username));
  }

  /**
   * Authorize user roles based on the provided UserRoleRelevance.
   *
   * @param dto The RoleSelectDto containing role information.
   * @return A collection of UserRoleRelevance entities after authorization.
   */
  @PostMapping("/authorize")
  @Operation(summary = "授权角色用户关联关系", description = "根据一组角色")
  @PreAuthorize("hasAuthority('menu.roles.authorize')")
  public Response<Collection<UserRoleRelevance>> authorize(@RequestBody RoleSelectDto dto) {
    return ok(service.authorize(dto));
  }

  /**
   * Cancel the authorization of user roles based on the provided UserRoleRelevance.
   *
   * @param dto The RoleSelectDto containing role information.
   * @return A response indicating the success of the unauthorization operation.
   */
  @PostMapping("/unauthorized")
  @Operation(summary = "取消授权角色用户关联关系", description = "根据角色用户关联关系取消授权")
  @PreAuthorize("hasPermission('menu.roles.unauthorized')")
  public Response<Void> unauthorized(@RequestBody RoleSelectDto dto) {
    service.unauthorized(dto);
    return Response.okay();
  }
}
