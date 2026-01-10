/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.menu.service.impl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.core.context.UserContext;
import org.simplepoint.data.amqp.annotation.AmqpRemoteService;
import org.simplepoint.data.initialize.DataInitializeManager;
import org.simplepoint.plugin.rbac.core.api.service.PermissionsService;
import org.simplepoint.plugin.rbac.core.api.service.RoleService;
import org.simplepoint.plugin.rbac.menu.api.entity.MenuPermissionsRelevance;
import org.simplepoint.plugin.rbac.menu.api.repository.MenuAncestorRepository;
import org.simplepoint.plugin.rbac.menu.api.repository.MenuPermissionsRelevanceRepository;
import org.simplepoint.plugin.rbac.menu.api.repository.MenuRepository;
import org.simplepoint.security.MenuChildren;
import org.simplepoint.security.entity.Menu;
import org.simplepoint.security.entity.MenuAncestor;
import org.simplepoint.security.entity.Permissions;
import org.simplepoint.security.entity.TreeMenu;
import org.simplepoint.security.pojo.dto.MenuPermissionsRelevanceDto;
import org.simplepoint.security.pojo.dto.ServiceMenuResult;
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

  private final MenuAncestorRepository menuAncestorRepository;

  private final RoleService roleService;

  private final PermissionsService permissionsService;

  private final MenuPermissionsRelevanceRepository menuPermissionsRelevanceRepository;

  private final DataInitializeManager dataInitializeManager;

  /**
   * Constructs a new {@code MenuServiceImpl} with the specified repository.
   *
   * @param repository                         the {@link MenuRepository} instance for data access
   * @param userContext                        the user context for retrieving current user information
   * @param detailsProviderService             the service for providing user details
   * @param menuAncestorRepository             the {@link MenuAncestorRepository} instance for menu ancestor operations
   * @param roleService                        the {@link RoleService} instance for role operations
   * @param permissionsService                 the {@link PermissionsService} instance for permission operations
   * @param menuPermissionsRelevanceRepository the {@link MenuPermissionsRelevanceRepository} instance for menu-permission relevance operations
   * @param dataInitializeManager              the {@link DataInitializeManager} instance for data initialization operations
   */
  public MenuServiceImpl(
      final MenuRepository repository,
      final UserContext<BaseUser> userContext,
      final DetailsProviderService detailsProviderService,
      final MenuAncestorRepository menuAncestorRepository,
      final RoleService roleService,
      final PermissionsService permissionsService,
      final MenuPermissionsRelevanceRepository menuPermissionsRelevanceRepository,
      final DataInitializeManager dataInitializeManager
  ) {
    super(repository, userContext, detailsProviderService);
    this.menuAncestorRepository = menuAncestorRepository;
    this.roleService = roleService;
    this.permissionsService = permissionsService;
    this.menuPermissionsRelevanceRepository = menuPermissionsRelevanceRepository;
    this.dataInitializeManager = dataInitializeManager;
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
  public <S extends Menu> S create(S entity) {
    if (entity.getAuthority() == null || entity.getAuthority().isEmpty()) {
      entity.setAuthority(entity.getPath().replace("/", "."));
    }
    var saved = super.create(entity);

    var parent = saved.getParent();

    if (parent != null && !parent.isEmpty()) {
      // Inherit ancestors from parent
      var ancestors = menuAncestorRepository.findAncestorIdsByChildIdIn(Set.of(parent));
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
      var deleteIds = new HashSet<>(ids);
      // 顺便删除子菜单
      Collection<String> child = menuAncestorRepository.findChildIdsByAncestorIds(ids);
      deleteIds.addAll(child);
      // 删除菜单关联的权限
      menuPermissionsRelevanceRepository.deleteAllByMenuIds(deleteIds);
      super.removeByIds(deleteIds);
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
    dataInitializeManager.execute("menu-permission-initialize", () -> {
      var blockingQueue = new ArrayDeque<>(data);
      while (!blockingQueue.isEmpty()) {
        MenuChildren current = blockingQueue.poll();
        Menu currentMenu = current.toMenu();
        this.create(currentMenu);
        Set<Permissions> permissions = current.getPermissions();
        if (permissions != null && !permissions.isEmpty()) {
          permissionsService.create(permissions);
          permissionsService.flush();
          this.authorize(
              new MenuPermissionsRelevanceDto(
                  currentMenu.getId(),
                  permissions.stream().map(Permissions::getId).collect(Collectors.toSet())
              )
          );
        }
        Set<MenuChildren> children = current.getChildren();
        if (children != null && !children.isEmpty()) {
          for (MenuChildren child : children) {
            child.setParent(currentMenu.getId());
            blockingQueue.offer(child);
          }
        }
      }
    });
  }

  /**
   * Retrieves the collection of menus accessible to the current user.
   *
   * @return a collection of {@link TreeMenu} entities available to the user
   */
  @Override
  public ServiceMenuResult routes() {
    UserContext<BaseUser> userContext = getUserContext();
    Set<ServiceMenuResult.ServiceEntry> services = new HashSet<>();
    if (userContext == null) {
      throw new IllegalArgumentException("User context is null or not logged in");
    }

    if (userContext.isSuperAdmin()) {
      return ServiceMenuResult.of(
          services,
          buildMenuTree(getRepository().loadAll(), services, true)
      );
    }
    Set<String> permissionIds = new HashSet<>();

    userContext.getPermissions().forEach((permission) -> permissionIds.add(permission.getId()));
    if (!permissionIds.isEmpty()) {
      // 查询全部已授权的菜单ID
      Set<String> authorizedMenuIds = new HashSet<>();
      // 加载菜单权限关联
      authorizedMenuIds.addAll(menuPermissionsRelevanceRepository.findAllMenuIdByPermissionIds(permissionIds));
      // 加载菜单祖先，确保完整的菜单树
      authorizedMenuIds.addAll(menuAncestorRepository.findAncestorIdsByChildIdIn(authorizedMenuIds));
      // Load menus by collected authorities
      Collection<Menu> menus = getRepository().loadByIds(authorizedMenuIds);
      // Build and return menu tree
      return ServiceMenuResult.of(
          services,
          buildMenuTree(menus, services, true)
      );
    }

    return ServiceMenuResult.EMPTY;
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
        buildMenuTree(ancestorMenus, new HashSet<>(), false)
            .stream().toList(),
        pageable,
        limit.getTotalElements()
    );
  }

  /**
   * Retrieves the collection of authorized menu identifiers based on the provided menu authority.
   *
   * @param menuId the menu authority string
   * @return a collection of authorized menu identifiers
   */
  @Override
  public Collection<String> authorized(String menuId) {
    return this.menuPermissionsRelevanceRepository.authorized(menuId);
  }

  /**
   * Authorizes the menu with the specified permissions based on the provided DTO.
   *
   * @param dto the data transfer object containing menu authority and permission authorities
   */
  @Override
  @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
  public void authorize(MenuPermissionsRelevanceDto dto) {
    Set<String> authorities = dto.getPermissionIds();
    Set<MenuPermissionsRelevance> saveAuthorities = new HashSet<>(authorities.size());
    for (String roleId : authorities) {
      MenuPermissionsRelevance relevance = new MenuPermissionsRelevance();
      relevance.setMenuId(dto.getMenuId());
      relevance.setPermissionId(roleId);
      saveAuthorities.add(relevance);
    }
    menuPermissionsRelevanceRepository.authorize(saveAuthorities);
  }

  /**
   * Revokes the authorization of the menu with the specified permissions based on the provided DTO.
   *
   * @param dto the data transfer object containing menu authority and permission authorities
   */
  @Override
  @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
  public void unauthorized(MenuPermissionsRelevanceDto dto) {
    menuPermissionsRelevanceRepository.unauthorized(dto.getMenuId(), dto.getPermissionIds());
  }

  /**
   * Builds a tree structure of menus from a flat collection.
   *
   * @param menus                  the flat collection of {@link Menu} entities
   * @param services               the set of services to consider
   * @param clearUnnecessaryFields whether to clear unnecessary fields from the Menu entities
   * @return a collection of {@link TreeMenu} representing the hierarchical menu structure
   */
  protected List<TreeMenu> buildMenuTree(Collection<Menu> menus, Set<ServiceMenuResult.ServiceEntry> services, boolean clearUnnecessaryFields) {
    // 1) Build node map
    final Map<String, TreeMenu> nodeMap = new HashMap<>(menus.size());
    for (Menu m : menus) {
      // 添加服务
      services.add(ServiceMenuResult.ServiceEntry.of(getServiceName(m.getComponent())));

      TreeMenu node = new TreeMenu();
      BeanUtils.copyProperties(m, node);
      node.setChildren(new ArrayList<>()); // always init

      nodeMap.put(m.getId(), node);
    }
    // 2) Build tree
    final List<TreeMenu> roots = new ArrayList<>();
    for (Menu m : menus) {

      TreeMenu current = nodeMap.get(m.getId());
      String parentId = m.getParent();

      boolean isRoot = (parentId == null || parentId.isBlank());
      TreeMenu parent = isRoot ? null : nodeMap.get(parentId);

      if (clearUnnecessaryFields) {
        clearUnnecessaryFields(current);
      }

      if (parent == null) {
        roots.add(current);
      } else {
        parent.getChildren().add(current);
      }
    }

    return roots;
  }


  private String getServiceName(String path) {
    if (path == null || path.isEmpty()) {
      return null;
    }

    int start = 0;
    // 跳过开头的 '/'
    if (path.charAt(0) == '/') {
      start = 1;
    }

    int end = path.indexOf('/', start);
    if (end == -1) {
      return path.substring(start);
    }

    return path.substring(start, end);
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
