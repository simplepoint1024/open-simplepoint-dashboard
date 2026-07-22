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
import org.simplepoint.plugin.rbac.core.api.pojo.command.ChangePasswordCommand;
import org.simplepoint.plugin.rbac.core.api.pojo.command.UserProfileUpdateCommand;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.UserRoleRelevanceDto;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RoleRelevanceVo;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.UserPickerItem;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.security.entity.User;
import org.simplepoint.security.entity.UserRoleRelevance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
@RequestMapping("/users")
@Tag(name = "用户管理", description = "用于管理系统中的用户")
public class UsersController extends BaseController<UsersService, User, String> {

  private static final int USER_PICKER_MAX_PAGE_SIZE = 50;

  private static final String USER_PICKER_ACCESS = "hasRole('Administrator')"
      + " or hasAnyAuthority('tenants.create', 'tenants.edit', 'users.view', 'users.create', 'users.edit')";

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
   */
  @GetMapping
  @Operation(summary = "分页查询用户", description = "根据提供的属性和分页参数，检索用户的分页列表")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('users.view')")
  public Response<Page<User>> limit(@RequestParam Map<String, String> attributes, Pageable pageable) {
    return limit(service.limit(attributes, pageable), User.class);
  }

  /**
   * Searches enabled users for JSON-Schema user-picker fields. The service
   * requires a meaningful email or phone prefix, so this endpoint never serves
   * as an unbounded user-directory listing.
   */
  @GetMapping("/picker/items")
  @Operation(summary = "搜索选人候选项", description = "按邮箱或手机号前缀分页搜索可选用户")
  @PreAuthorize(USER_PICKER_ACCESS)
  public Response<Page<UserPickerItem>> pickerItems(
      @RequestParam(name = "keyword", required = false) String keyword,
      Pageable pageable
  ) {
    return ok(service.searchPickerItems(keyword, boundedPickerPageable(pageable)));
  }

  /** Resolves persisted picker IDs without exposing a broad directory query. */
  @GetMapping("/picker/selected")
  @Operation(summary = "回显已选用户", description = "批量解析选人字段中已经保存的用户ID")
  @PreAuthorize(USER_PICKER_ACCESS)
  public Response<Collection<UserPickerItem>> selectedPickerItems(@RequestParam("ids") String ids) {
    return ok(service.resolvePickerItems(StringUtil.stringToSet(ids)));
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
  @PreAuthorize("hasRole('Administrator') or hasAuthority('users.create')")
  public Response<User> add(@RequestBody User data) throws Exception {
    return ok(service.create(data));
  }

  /**
   * Updates an existing user in the system.
   *
   * @param data the User object with updated information
   * @return a response containing the updated User object
   */
  @PutMapping
  @Operation(summary = "修改用户", description = "修改一个已存在的用户信息")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('users.edit')")
  public Response<User> modify(@RequestBody User data) {
    return ok(service.modifyById(data));
  }

  /**
   * Deletes one or more users identified by their IDs.
   *
   * @param ids a comma-separated string of user IDs to be deleted
   * @return a response indicating the success of the deletion operation
   */
  @DeleteMapping
  @Operation(summary = "删除用户", description = "根据提供的用户ID集合，删除一个或多个用户")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('users.delete')")
  public Response<Set<String>> remove(@RequestParam("ids") String ids) {
    Set<String> idSet = StringUtil.stringToSet(ids);
    service.removeByIds(idSet);
    return ok(idSet);
  }

  /**
   * Retrieves a collection of role authorities associated with a specific userId.
   *
   * @param userId The userId to filter the role authorities.
   * @return A response containing a collection of role authorities for the given userId.
   */
  @GetMapping("/authorized")
  @Operation(summary = "获取用户角色权限", description = "根据用户名获取该用户")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('users.config.role')")
  public Response<Collection<String>> authorized(@RequestParam("userId") String userId) {
    return ok(service.authorized(userId));
  }

  /**
   * Authorize user roles based on the provided UserRoleRelevance.
   *
   * @param dto The RoleSelectDto containing role information.
   * @return A collection of UserRoleRelevance entities after authorization.
   */
  @PostMapping("/authorize")
  @Operation(summary = "授权角色用户关联关系", description = "根据一组角色")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('users.config.role')")
  public Response<Collection<UserRoleRelevance>> authorize(@RequestBody UserRoleRelevanceDto dto) {
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
  @PreAuthorize("hasRole('Administrator') or hasAuthority('users.config.role')")
  public Response<Void> unauthorized(@RequestBody UserRoleRelevanceDto dto) {
    service.unauthorized(dto);
    return Response.okay();
  }

  /**
   * @ Get Mapping.
   */
  @GetMapping("/role-candidates")
  @Operation(summary = "获取用户管理可分配角色列表", description = "获取用户管理中可分配的角色列表，始终使用默认租户范围")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('users.config.role')")
  public Response<Page<RoleRelevanceVo>> roleCandidates(Pageable pageable) {
    return ok(service.roleCandidates(pageable));
  }

  /**
   * Changes the password for the currently authenticated user.
   *
   * @param command the change-password command with current, new, and confirm passwords
   * @return an empty success response
   */
  @PostMapping("/change-password")
  @Operation(summary = "修改当前用户密码", description = "验证旧密码后，将当前用户密码修改为新密码")
  @PreAuthorize("isAuthenticated()")
  public Response<Void> changePassword(@RequestBody ChangePasswordCommand command) {
    service.changePassword(currentUserId(), command);
    return Response.okay();
  }

  /**
   * Returns the persisted profile of the currently authenticated user.
   */
  @GetMapping("/me")
  @Operation(summary = "查询当前用户资料", description = "返回当前登录用户可编辑的完整个人资料")
  @PreAuthorize("isAuthenticated()")
  public Response<User> currentProfile() {
    return ok(service.findByIdForAuthorization(currentUserId())
        .orElseThrow(() -> new IllegalArgumentException("用户不存在")));
  }

  /**
   * Updates only the current user's personal profile fields.
   */
  @PutMapping("/me")
  @Operation(summary = "修改当前用户资料", description = "修改当前登录用户的个人资料和 OSS 头像")
  @PreAuthorize("isAuthenticated()")
  public Response<User> updateCurrentProfile(@RequestBody UserProfileUpdateCommand command) {
    return ok(service.updateCurrentProfile(currentUserId(), command));
  }

  private String currentUserId() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated()) {
      throw new IllegalStateException("当前未认证用户");
    }
    if (auth.getPrincipal() instanceof User user && user.getId() != null) {
      return user.getId();
    }
    return auth.getName();
  }

  private Pageable boundedPickerPageable(Pageable pageable) {
    int page = Math.max(0, pageable.getPageNumber());
    int size = Math.min(Math.max(1, pageable.getPageSize()), USER_PICKER_MAX_PAGE_SIZE);
    return PageRequest.of(page, size);
  }
}
