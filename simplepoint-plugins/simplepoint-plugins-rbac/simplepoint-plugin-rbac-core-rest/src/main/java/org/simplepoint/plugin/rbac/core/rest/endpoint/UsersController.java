/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.rest.endpoint;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.security.entity.User;
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
 * REST controller for managing User entities in the RBAC (Role-Based Access Control) system.
 * This controller provides endpoints for retrieving, creating, updating, and deleting users.
 */
@RestController
@RequestMapping("/user")
@Tag(name = "用户管理", description = "用于管理系统中的用户")
public class UsersController extends BaseController<UsersService, User, String> {

  /**
   * Constructs a UsersController with the specified service.
   *
   * @param service The UsersService instance to be used.
   */
  public UsersController(final UsersService service) {
    super(service);
  }

  /**
   * Retrieves a paginated list of users based on the provided attributes and pageable parameters.
   *
   * @param attributes a map of attributes to filter the users
   * @param pageable   the pagination and sorting information
   * @return a paginated response containing users that match the given attributes
   * @throws Exception if an error occurs during retrieval
   */
  @GetMapping
  @Operation(summary = "分页查询用户", description = "根据提供的属性和分页参数，检索用户的分页列表")
  public Response<Page<User>> limit(@RequestParam Map<String, String> attributes, Pageable pageable)
      throws Exception {
    return limit(service.limit(attributes, pageable), User.class);
  }

  /**
   * Adds a new user to the system.
   *
   * @param data the User object to be added
   * @return a response containing the added User object
   * @throws Exception if an error occurs during creation
   */
  @PostMapping
  @Operation(summary = "添加用户", description = "添加一个新的用户到系统中")
  public Response<User> add(@RequestBody User data) throws Exception {
    return ok(service.add(data));
  }

  /**
   * Updates an existing user in the system.
   *
   * @param data the User object with updated information
   * @return a response containing the updated User object
   * @throws Exception if an error occurs during the update
   */
  @PutMapping
  @Operation(summary = "修改用户", description = "修改一个已存在的用户信息")
  public Response<User> modify(@RequestBody User data) throws Exception {
    return ok(service.modifyById(data));
  }

  /**
   * Deletes one or more users identified by their IDs.
   *
   * @param ids a comma-separated string of user IDs to be deleted
   * @return a response indicating the success of the deletion operation
   * @throws Exception if an error occurs during deletion
   */
  @DeleteMapping
  @Operation(summary = "删除用户", description = "根据提供的用户ID集合，删除一个或多个用户")
  public Response<Set<String>> remove(@RequestParam("ids") String ids) throws Exception {
    Set<String> idSet = StringUtil.stringToSet(ids);
    service.removeByIds(idSet);
    return ok(idSet);
  }
}
