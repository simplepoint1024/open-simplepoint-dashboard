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
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.security.entity.Menu;
import org.simplepoint.security.entity.TreeMenu;
import org.simplepoint.security.service.MenuService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
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
  @Operation(summary = "分页查询菜单", description = "根据提供的属性和分页参数，检索菜单的分页列表")
  public Response<Page<TreeMenu>> limit(@RequestParam Map<String, String> attributes, Pageable pageable) {
    return Response.limit(service.limitTree(attributes, pageable), TreeMenu.class);
  }

  /**
   * Retrieves the menu list for the currently logged-in user.
   *
   * @return a collection of menu records accessible to the current user wrapped in {@link Response}
   */
  @GetMapping("/routes")
  @Operation(summary = "获取用户菜单", description = "获取当前登录用户的菜单列表")
  public Response<Page<TreeMenu>> routes() {
    return Response.okay(new PageImpl<>(service.routes().stream().toList(), Pageable.unpaged(), service.routes().size()));
  }

  /**
   * Adds a new menu record.
   *
   * @param data the {@link Menu} instance to be added
   * @return the added menu record wrapped in {@link Response}
   * @throws Exception if an error occurs during creation
   */
  @PostMapping
  @Operation(summary = "添加新菜单", description = "将新的菜单添加到系统中")
  public Response<Menu> add(@RequestBody Menu data) throws Exception {
    return ok(service.add(data));
  }

  /**
   * Modifies an existing menu record.
   *
   * @param data the {@link Menu} instance to be updated
   * @return the updated menu record wrapped in {@link Response}
   * @throws Exception if an error occurs during modification
   */
  @PutMapping
  @Operation(summary = "更新菜单信息", description = "更新系统中现有菜单的信息")
  public Response<Menu> modify(@RequestBody Menu data) throws Exception {
    return ok(service.modifyById(data));
  }

  /**
   * Removes menu records by their IDs.
   *
   * @param ids a comma-separated string of menu IDs to be removed
   * @return a success response indicating removal completion
   * @throws Exception if an error occurs during deletion
   */
  @DeleteMapping
  @Operation(summary = "删除菜单", description = "根据提供的菜单ID列表，删除对应的菜单记录")
  public Response<Set<String>> remove(@RequestParam("ids") String ids) throws Exception {
    Set<String> idSet = StringUtil.stringToSet(ids);
    service.removeByIds(idSet);
    return ok(idSet);
  }
}
