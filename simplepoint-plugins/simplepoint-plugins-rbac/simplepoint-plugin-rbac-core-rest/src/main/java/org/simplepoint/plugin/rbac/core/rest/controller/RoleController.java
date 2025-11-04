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
import org.simplepoint.plugin.rbac.core.api.service.RoleService;
import org.simplepoint.security.entity.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
   * @throws Exception if an error occurs during retrieval
   */
  @GetMapping
  @Operation(summary = "分页查询角色", description = "根据提供的属性和分页参数，检索角色的分页列表")
  public Response<Page<Role>> limit(@RequestParam Map<String, String> attributes, Pageable pageable)
      throws Exception {
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
  public Response<Role> add(@RequestBody Role data) throws Exception {
    return ok(service.add(data));
  }

  /**
   * Updates an existing role in the system.
   *
   * @param data the Role object with updated information
   * @return a response containing the updated Role object
   * @throws Exception if an error occurs during the update
   */
  @PutMapping
  @Operation(summary = "更新角色信息", description = "更新系统中现有角色的信息")
  public Response<Role> modify(@RequestBody Role data) throws Exception {
    return ok(service.modifyById(data));
  }

  /**
   * Deletes one or more roles identified by their IDs.
   *
   * @param ids a comma-separated string of role IDs to be deleted
   * @return a response indicating the success of the deletion operation
   * @throws Exception if an error occurs during deletion
   */
  @DeleteMapping
  @Operation(summary = "删除角色", description = "根据提供的角色ID删除一个或多个角色")
  public Response<Set<String>> remove(@RequestParam("ids") String ids) throws Exception {
    Set<String> idSet = StringUtil.stringToSet(ids);
    service.removeByIds(idSet);
    return ok(idSet);
  }
}
