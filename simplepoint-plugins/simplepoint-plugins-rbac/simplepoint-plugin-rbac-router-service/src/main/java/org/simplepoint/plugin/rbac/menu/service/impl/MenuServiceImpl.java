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
import java.util.Comparator;
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
import org.simplepoint.plugin.rbac.core.api.service.PermissionsService;
import org.simplepoint.plugin.rbac.core.api.service.ResourcesPermissionsRelevanceService;
import org.simplepoint.plugin.rbac.core.api.service.RoleService;
import org.simplepoint.plugin.rbac.menu.api.repository.MenuAncestorRepository;
import org.simplepoint.plugin.rbac.menu.api.repository.MenuRepository;
import org.simplepoint.security.MenuChildren;
import org.simplepoint.security.entity.Menu;
import org.simplepoint.security.entity.MenuAncestor;
import org.simplepoint.security.entity.Permissions;
import org.simplepoint.security.entity.ResourcesPermissionsRelevance;
import org.simplepoint.security.entity.RolePermissionsRelevance;
import org.simplepoint.security.entity.TreeMenu;
import org.simplepoint.security.pojo.dto.MenuPermissionsRelevanceDto;
import org.simplepoint.security.service.MenuService;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

  private final ResourcesPermissionsRelevanceService resourcesPermissionsRelevanceService;

  private final MenuAncestorRepository menuAncestorRepository;

  private final RoleService roleService;

  private final PermissionsService permissionsService;

  /**
   * Constructs a new {@code MenuServiceImpl} with the specified repository.
   *
   * @param repository             the {@link MenuRepository} instance for data access
   * @param userContext            the user context for retrieving current user information
   * @param detailsProviderService the service for providing user details
   * @param menuAncestorRepository the {@link MenuAncestorRepository} instance for menu ancestor operations
   * @param roleService            the {@link RoleService} instance for role operations
   * @param permissionsService     the {@link PermissionsService} instance for permission operations
   */
  public MenuServiceImpl(
      final MenuRepository repository,
      final UserContext<BaseUser> userContext,
      final DetailsProviderService detailsProviderService,
      final MenuAncestorRepository menuAncestorRepository,
      final RoleService roleService,
      final PermissionsService permissionsService,
      final ResourcesPermissionsRelevanceService resourcesPermissionsRelevanceService
  ) {
    super(repository, userContext, detailsProviderService);
    this.menuAncestorRepository = menuAncestorRepository;
    this.roleService = roleService;
    this.permissionsService = permissionsService;
    this.resourcesPermissionsRelevanceService = resourcesPermissionsRelevanceService;
  }

  /**
   * Adds a new menu entity to the system.
   *
   * @param entity the {@link Menu} entity to be added
   * @param <S>    the type of the menu entity
   * @return the saved {@link Menu} entity
   */
  @Override
  @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
  public <S extends Menu> S persist(S entity) {
    if (entity.getAuthority() == null || entity.getAuthority().isEmpty()) {
      entity.setAuthority(entity.getPath().replace("/", ":"));
    }
    var saved = super.persist(entity);

    var parent = saved.getParent();

    if (parent != null && !parent.isEmpty()) {
      // Inherit ancestors from parent
      var ancestors = menuAncestorRepository.findAncestorIdsByChildIds(Set.of(parent));
      List<MenuAncestor> toSave = new ArrayList<>();
      for (String ancestorUuid : ancestors) {
        MenuAncestor ma = new MenuAncestor();
        ma.setChildId(saved.getId());
        ma.setAncestorId(ancestorUuid);
        toSave.add(ma);
      }
      // Add parent as direct ancestor
      MenuAncestor directAncestor = new MenuAncestor();
      directAncestor.setChildId(saved.getId());
      directAncestor.setAncestorId(parent);
      toSave.add(directAncestor);
      menuAncestorRepository.saveAll(toSave);
    }
    return saved;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
  public void removeById(String id) {
    this.removeByIds(Set.of(id));
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
  public void removeByIds(Collection<String> ids) {
    if (ids != null && !ids.isEmpty()) {
      var allIds = new HashSet<>(ids);
      // 顺便删除子菜单
      Collection<String> child = menuAncestorRepository.findChildIdsByAncestorIds(ids);
      allIds.addAll(child);
      Collection<String> childIdsByAncestorIds = menuAncestorRepository.findChildIdsByAncestorIds(allIds);
      if (childIdsByAncestorIds != null && !childIdsByAncestorIds.isEmpty()) {
        menuAncestorRepository.deleteChild(childIdsByAncestorIds);
        // 删除菜单关联的权限
        List<String> menuAuthorities = getRepository().loadAuthoritiesByMenuIds(childIdsByAncestorIds);
        if (!menuAuthorities.isEmpty()) {
          resourcesPermissionsRelevanceService.removeAllByAuthorities(menuAuthorities);
        }
      }
      super.removeByIds(allIds);
    }
  }

  /**
   * Synchronizes the menu data with the provided set of {@link MenuChildren}.
   *
   * @param data the set of menu children to synchronize
   */
  @Override
  @Transactional(propagation = Propagation.SUPPORTS, rollbackFor = Exception.class)
  public void sync(Set<MenuChildren> data) {
    // Map temp menu id (from MenuChildren) -> persisted real id
    Map<String, String> idMap = new HashMap<>();

    // Flatten tree in BFS order, making sure parent is processed before children
    Queue<MenuChildren> queue = new LinkedBlockingQueue<>(data);
    while (!queue.isEmpty()) {
      MenuChildren current = queue.poll();
      if (current.getId() == null) {
        // Assign a temporary id only for in-memory parent-child wiring
        current.setId(UUID.randomUUID().toString());
      }
      if (current.getChildren() != null) {
        queue.addAll(current.getChildren().stream()
            .peek(child -> child.setParent(current.getId())).toList());
      }

      // Build Menu entity without carrying over the temp id
      Menu menu = new Menu();
      BeanUtils.copyProperties(current, menu);
      menu.setId(null); // ensure Hibernate treats it as new if not already existing by path

      // Resolve real parent id from map (parent always processed earlier in BFS)
      if (current.getParent() != null) {
        String realParentId = idMap.get(current.getParent());
        menu.setParent(realParentId); // may be null for roots
      }

      try {
        // Try to find existing menu by unique path to avoid duplicates and get real id
        Map<String, String> attrs = new HashMap<>();
        attrs.put("path", menu.getPath());
        List<Menu> existing = getRepository().findAll(attrs);

        String realId;
        if (existing == null || existing.isEmpty()) {
          // Not exists -> persist; add() will also build ancestors using resolved parent id
          Menu saved = this.persist(menu);
          realId = saved.getId();
          Set<Permissions> permissions = current.getPermissions();
          if (permissions != null && !permissions.isEmpty()) {
            for (Permissions permission : permissions) {
              try {
                this.permissionsService.persist(permission);
                this.resourcesPermissionsRelevanceService.authorize(Set.of(
                    new ResourcesPermissionsRelevance(current.getAuthority(), permission.getAuthority())
                ));
              } catch (Exception e) {
                log.warn("Failed to sync permission by authority: {}", permission.getAuthority());
              }
            }
          }
        } else {
          // Already exists -> just use its id for mapping; optionally parent adjustments can be handled later
          realId = existing.getFirst().getId();
        }

        // Map temp id to real id for children
        idMap.put(current.getId(), realId);
      } catch (Exception e) {
        log.warn("Failed to sync menu by path: {}", menu.getPath(), e);
      }
    }
  }

  /**
   * Retrieves the collection of menus accessible to the current user.
   *
   * @return a collection of {@link TreeMenu} entities available to the user
   */
  @Override
  public Collection<TreeMenu> routes() {
    UserContext<BaseUser> userContext = getUserContext();
    if (userContext == null) {
      throw new IllegalArgumentException("User context is null or not logged in");
    }

    if (userContext.isSuperAdmin()) {
      return buildMenuTree(getRepository().loadAll(), true);
    }
    // Extract roles from granted authorities
    Set<String> permissionsSet = userContext.getPermissions();
    Set<String> roles = new HashSet<>();
    // Extract role names from authorities
    for (var permission : permissionsSet) {
      if (permission.startsWith("ROLE_")) {
        roles.add(permission.substring(5));
      }
    }

    if (!roles.isEmpty()) {
      var rolePermissions = roleService.loadPermissionsByRoleAuthorities(roles);
      if (!rolePermissions.isEmpty()) {
        List<String> permissions = rolePermissions.stream().map(RolePermissionsRelevance::getPermissionAuthority).toList();
        Collection<ResourcesPermissionsRelevance> menuPermissions = getRepository().loadPermissionsByPermissionAuthorities(permissions);
        // Collect menu authorities from permissions
        List<String> menuAuthorities = new ArrayList<>(menuPermissions.stream().map(ResourcesPermissionsRelevance::getResourceAuthority).toList());
        // 根据已授权的菜单权限标识加载菜单ID
        List<String> authorizedMenuIds = getRepository().loadMenuIdsByAuthorities(menuAuthorities);
        // Include ancestor menus to ensure proper tree structure
        authorizedMenuIds.addAll(menuAncestorRepository.findAncestorIdsByChildIds(authorizedMenuIds));
        // Load menus by collected authorities
        Collection<Menu> menus = getRepository().loadByIds(authorizedMenuIds);
        // Build and return menu tree
        return buildMenuTree(menus, true);
      }
    }
    throw new IllegalArgumentException("Invalid user context");
  }

  /**
   * Retrieves a paginated list of tree-structured menus based on the specified filter attributes.
   *
   * @param attributes a map of filtering attributes
   * @param pageable   the pagination information
   * @return a paginated list of {@link TreeMenu} entities matching the filters
   */
  @Override
  public Page<TreeMenu> limitTree(Map<String, String> attributes, Pageable pageable) {
    attributes.put("parent", "is:null:");
    Page<Menu> limit = limit(attributes, pageable);
    List<String> rootUuids = limit.map(Menu::getId).stream().toList();
    Collection<String> ancestorUuids = menuAncestorRepository.findChildIdsByAncestorIds(rootUuids);
    List<Menu> ancestorMenus =
        new ArrayList<>(findAllByIds(ancestorUuids)
            .stream().sorted(Comparator.comparing(Menu::getSort, Comparator.nullsLast(Integer::compareTo))).toList());
    ancestorMenus.addAll(limit.getContent());
    return new PageImpl<>(
        buildMenuTree(ancestorMenus, false)
            .stream().toList(),
        pageable,
        limit.getTotalElements()
    );
  }

  /**
   * Retrieves the collection of authorized menu identifiers based on the provided menu authority.
   *
   * @param menuAuthority the menu authority string
   * @return a collection of authorized menu identifiers
   */
  @Override
  public Collection<String> authorized(String menuAuthority) {
    return this.resourcesPermissionsRelevanceService.authorized(menuAuthority);
  }

  /**
   * Authorizes the menu with the specified permissions based on the provided DTO.
   *
   * @param dto the data transfer object containing menu authority and permission authorities
   */
  @Override
  @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
  public void authorize(MenuPermissionsRelevanceDto dto) {
    Set<String> authorities = dto.getPermissionAuthorities();
    Set<ResourcesPermissionsRelevance> saveAuthorities = new HashSet<>(authorities.size());
    for (String roleAuthority : authorities) {
      ResourcesPermissionsRelevance relevance = new ResourcesPermissionsRelevance();
      relevance.setResourceAuthority(dto.getMenuAuthority());
      relevance.setPermissionAuthority(roleAuthority);
      saveAuthorities.add(relevance);
    }
    resourcesPermissionsRelevanceService.authorize(saveAuthorities);
  }

  /**
   * Revokes the authorization of the menu with the specified permissions based on the provided DTO.
   *
   * @param dto the data transfer object containing menu authority and permission authorities
   */
  @Override
  @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
  public void unauthorized(MenuPermissionsRelevanceDto dto) {
    resourcesPermissionsRelevanceService.unauthorize(dto.getMenuAuthority(), dto.getPermissionAuthorities());
  }

  /**
   * Builds a tree structure of menus from a flat collection.
   *
   * @param menus the flat collection of {@link Menu} entities
   * @return a collection of {@link TreeMenu} representing the hierarchical menu structure
   */
  protected Collection<TreeMenu> buildMenuTree(Collection<Menu> menus, boolean clearUnnecessaryFields) {
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
      nodeMap.put(m.getId(), node);
    }

    // 2) Link children to parent and collect roots
    final List<TreeMenu> roots = new ArrayList<>();
    for (Menu m : menus) {
      String parentId = m.getParent();
      TreeMenu current = nodeMap.get(m.getId());
      if (parentId == null || parentId.isBlank()) {
        if (clearUnnecessaryFields) {
          clearUnnecessaryFields(current);
        }
        roots.add(current);
        continue;
      }
      TreeMenu parent = nodeMap.get(parentId);
      if (parent != null) {
        if (parent.getChildren() == null) {
          parent.setChildren(new ArrayList<>());
        }
        if (clearUnnecessaryFields) {
          clearUnnecessaryFields(current);
        }
        parent.getChildren().add(current);
      } else {
        // Orphan node, treat as root
        if (clearUnnecessaryFields) {
          clearUnnecessaryFields(current);
        }
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
  }
}
