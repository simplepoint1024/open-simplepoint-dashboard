/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.menu.rest.endpoint;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.security.entity.Menu;
import org.simplepoint.security.entity.TreeMenu;
import org.simplepoint.security.pojo.dto.MenuPermissionsRelevanceDto;
import org.simplepoint.security.pojo.dto.ServiceMenuResult;
import org.simplepoint.security.service.MenuService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
 * REST controller for managing menu entities.
 *
 * <p>This controller provides CRUD operations for {@link Menu} objects,
 * allowing creation, modification, deletion, and retrieval of menu records.</p>
 *
 * @author JinxuLiu
 * @since 1.0
 */
@RestController
@RequestMapping("/menus")
@Tag(name = "菜单管理", description = "用于管理系统中的菜单")
public class MenusController extends BaseController<MenuService, Menu, String> {

  /**
   * Constructs a new {@code MenusController} with the specified service.
   *
   * @param service the {@link MenuService} instance providing menu operations
   */
  public MenusController(final MenuService service) {
    super(service);
  }

  /**
   * Retrieves a paginated list of menus based on the specified filter attributes.
   *
   * @param attributes a map of filtering attributes
   * @param pageable   pagination information
   * @return a paginated list of menu records wrapped in {@link Response}
   */
  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('menus.view')")
  @Operation(summary = "分页查询菜单", description = "根据提供的属性和分页参数，检索菜单的分页列表")
  public Response<Page<TreeMenu>> limit(@RequestParam Map<String, String> attributes, Pageable pageable) {
    if (!pageable.getSort().isSorted()) {
      pageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.ASC, "sort"));
    }
    return Response.limit(service.limitTree(attributes, pageable), TreeMenu.class);
  }

  /**
   * Retrieves the menu list for the currently logged-in user.
   *
   * @return a collection of menu records accessible to the current user wrapped in {@link Response}
   */
  @GetMapping("/service-routes")
  @Operation(summary = "获取用户菜单", description = "获取当前登录用户的菜单列表")
  public Response<ServiceMenuResult> routes() {
    return Response.okay(service.routes());
  }


  /**
   * Adds a new menu record.
   *
   * @param data the {@link Menu} instance to be added
   * @return the added menu record wrapped in {@link Response}
   * @throws Exception if an error occurs during creation
   */
  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('menus.create')")
  @Operation(summary = "添加新菜单", description = "将新的菜单添加到系统中")
  public Response<Menu> add(@RequestBody Menu data) throws Exception {
    return ok(service.create(data));
  }

  /**
   * Modifies an existing menu record.
   *
   * @param data the {@link Menu} instance to be updated
   * @return the updated menu record wrapped in {@link Response}
   */
  @PutMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('menus.edit')")
  @Operation(summary = "更新菜单信息", description = "更新系统中现有菜单的信息")
  public Response<Menu> modify(@RequestBody Menu data) {
    return ok(service.modifyById(data));
  }

  /**
   * Removes menu records by their IDs.
   *
   * @param ids a comma-separated string of menu IDs to be removed
   * @return a success response indicating removal completion
   */
  @DeleteMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('menus.delete')")
  @Operation(summary = "删除菜单", description = "根据提供的菜单ID列表，删除对应的菜单记录")
  public Response<Set<String>> remove(@RequestParam("ids") String ids) {
    Set<String> idSet = StringUtil.stringToSet(ids);
    service.removeByIds(idSet);
    return ok(idSet);
  }

  /**
   * Retrieves the authorized menu permissions based on the provided menu authority.
   *
   * @param menuId the menu authority string
   * @return a collection of authorized menu permission identifiers wrapped in {@link Response}
   */
  @GetMapping("/authorized")
  @Operation(summary = "获取已授权的菜单权限点", description = "获取指定角色已授权的菜单权限点")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('menus.config.permission')")
  public Response<Collection<String>> authorized(@RequestParam("menuId") String menuId) {
    return ok(service.authorized(menuId));
  }

  /**
   * Assigns permissions to a menu.
   *
   * @param dto the MenuPermissionsRelevanceDto containing menu and permissions information
   * @return a collection of UserRoleRelevance representing the assigned permissions
   */
  @PostMapping("/authorize")
  @Operation(summary = "为菜单分配权限", description = "为菜单分配权限点")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('menus.config.permission')")
  public Response<Void> authorize(@RequestBody MenuPermissionsRelevanceDto dto) {
    service.authorize(dto);
    return Response.okay();
  }

  /**
   * Revokes permissions from a menu.
   *
   * @param dto the MenuPermissionsRelevanceDto containing menu and permissions information
   * @return a success response indicating revocation completion
   */
  @PostMapping("/unauthorized")
  @Operation(summary = "取消已授权的菜单权限", description = "取消菜单已分配的权限点")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('menus.config.permission')")
  public Response<Void> unauthorized(@RequestBody MenuPermissionsRelevanceDto dto) {
    service.unauthorized(dto);
    return Response.okay();
  }
}
