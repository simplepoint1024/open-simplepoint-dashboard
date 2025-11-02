/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.menu.service.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.core.context.UserContext;
import org.simplepoint.data.amqp.annotation.AmqpRemoteService;
import org.simplepoint.plugin.rbac.menu.api.repository.MenuRepository;
import org.simplepoint.plugin.rbac.menu.api.repository.TreeMenuRepository;
import org.simplepoint.security.MenuChildren;
import org.simplepoint.security.entity.Menu;
import org.simplepoint.security.entity.TreeMenu;
import org.simplepoint.security.service.MenuService;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Implementation of {@link MenuService} providing business logic for menu management.
 *
 * <p>This service handles CRUD operations for menus by interacting with {@link MenuRepository}.
 * It extends {@link BaseServiceImpl} to inherit standard data operations.</p>
 *
 * @author Your Name
 * @since 1.0
 */
@Slf4j
@AmqpRemoteService
public class MenuServiceImpl
    extends BaseServiceImpl<MenuRepository, Menu, String>
    implements MenuService {

  private final TreeMenuRepository treeMenuRepository;

  /**
   * Constructs a new {@code MenuServiceImpl} with the specified repository.
   *
   * @param repository             the {@link MenuRepository} instance for data access
   * @param userContext            the user context for retrieving current user information
   * @param detailsProviderService the service for providing user details
   * @param treeMenuRepository     the {@link TreeMenuRepository} instance for tree menu operations
   */
  public MenuServiceImpl(
      final MenuRepository repository,
      final UserContext<BaseUser> userContext,
      final DetailsProviderService detailsProviderService,
      TreeMenuRepository treeMenuRepository
  ) {
    super(repository, userContext, detailsProviderService);
    this.treeMenuRepository = treeMenuRepository;
  }

  @Override
  public <S extends Menu> Page<S> limit(Map<String, String> attributes, Pageable pageable)
      throws Exception {
    //F.processing(limit,
    //s -> I18nContextHolder.localize(
    //s.getContent(),
    //Menu::getLabel,
    //Menu::setLabel,
    //attributes.containsKey(I18nContextHolder.DISABLE_I18N)
    //));
    return super.limit(attributes, pageable);
  }

  @Override
  public <S extends Menu> S add(S entity) throws Exception {
    if (entity.getUuid() == null || entity.getUuid().isEmpty()) {
      entity.setUuid(UUID.randomUUID().toString());
    }
    return super.add(entity);
  }

  @Override
  public void sync(Set<MenuChildren> data) {
    Set<Menu> menus = new HashSet<>();
    Queue<MenuChildren> queue = new LinkedBlockingQueue<>(data);
    while (!queue.isEmpty()) {
      MenuChildren current = queue.poll();
      if (current.getUuid() == null) {
        current.setUuid(UUID.randomUUID().toString());
      }
      if (current.getChildren() != null) {
        queue.addAll(current.getChildren().stream()
            .peek(child -> child.setParent(current.getUuid())).toList());
      }
      Menu menu = new Menu();
      BeanUtils.copyProperties(current, menu);
      menus.add(menu);
    }
    for (Menu menu : menus) {
      Menu example = new Menu();
      example.setPath(menu.getPath());
      if (!exists(example)) {
        try {
          this.add(menu);
        } catch (Exception e) {
          log.warn("Failed to add menu: {}", menu.getPath(), e);
        }
      }
    }
  }

  @Override
  public Collection<TreeMenu> routes() {
    UserContext<BaseUser> userContext = getUserContext();
    String username = userContext.getName();
    if (userContext.getDetails().superAdmin()) {
      return buildMenuTree(getRepository().findAllByOrderBySortAsc());
    }
    return buildMenuTree(getRepository().findUserMenus(username));
  }

  @Override
  public Page<TreeMenu> limitTree(Map<String, String> attributes, Pageable pageable) {
    attributes.put("parent", "is:null:");
    Page<Menu> limit = getRepository().limit(attributes, pageable);
    List<String> paths = limit.map(Menu::getPath).stream().toList();
    return new PageImpl<>(
        buildMenuTree(
            !paths.isEmpty()
                ? treeMenuRepository.findInPathStartingWith(paths)
                : limit.getContent()
        ).stream().toList(),
        pageable,
        limit.getTotalElements()
    );
  }

  /**
   * Builds a tree structure of menus from a flat collection.
   *
   * @param menus the flat collection of {@link Menu} entities
   * @return a collection of {@link TreeMenu} representing the hierarchical menu structure
   */
  protected Collection<TreeMenu> buildMenuTree(Collection<Menu> menus) {
    // 1) Create TreeMenu nodes map keyed by uuid
    final Map<String, TreeMenu> nodeMap = new HashMap<>();
    for (Menu m : menus) {
      TreeMenu node = new TreeMenu();
      BeanUtils.copyProperties(m, node); // copy common fields
      if (node.getChildren() == null) {
        node.setChildren(new ArrayList<>());
      } else {
        node.getChildren().clear(); // ensure rebuilding tree from scratch
      }
      nodeMap.put(m.getUuid(), node);
    }

    // 2) Link children to parent and collect roots
    final List<TreeMenu> roots = new ArrayList<>();
    for (Menu m : menus) {
      String parentId = m.getParent();
      TreeMenu current = nodeMap.get(m.getUuid());
      if (parentId == null || parentId.isBlank()) {
        clearUnnecessaryFields(current);
        roots.add(current);
        continue;
      }
      TreeMenu parent = nodeMap.get(parentId);
      if (parent != null) {
        if (parent.getChildren() == null) {
          parent.setChildren(new ArrayList<>());
        }
        clearUnnecessaryFields(current);
        parent.getChildren().add(current);
      } else {
        // Orphan node, treat as root
        clearUnnecessaryFields(current);
        roots.add(current);
      }
    }
    return roots;
  }

  /**
   * Clears unnecessary fields from the Menu entity before returning it to the client.
   *
   * @param menu the Menu entity to be cleaned
   */
  private void clearUnnecessaryFields(Menu menu) {
    menu.setCreatedBy(null);
    menu.setCreatedAt(null);
    menu.setUpdatedBy(null);
    menu.setUpdatedAt(null);
    menu.setParent(null);
    menu.setId(null);
    menu.setUuid(null);
  }
}

