/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.menu.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.data.amqp.annotation.AmqpRemoteService;
import org.simplepoint.data.initialize.DataInitializeExecutor;
import org.simplepoint.plugin.auditing.logging.api.pojo.command.PermissionChangeLogRecordCommand;
import org.simplepoint.plugin.auditing.logging.api.service.PermissionChangeLogRemoteService;
import org.simplepoint.plugin.rbac.core.api.service.PermissionsService;
import org.simplepoint.plugin.rbac.menu.api.entity.MenuFeatureRelevance;
import org.simplepoint.plugin.rbac.menu.api.repository.MenuAncestorRepository;
import org.simplepoint.plugin.rbac.menu.api.repository.MenuFeatureRelevanceRepository;
import org.simplepoint.plugin.rbac.menu.api.repository.MenuRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.Feature;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.FeaturePermissionsRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.service.FeatureService;
import org.simplepoint.security.MenuChildren;
import org.simplepoint.security.MenuFeatureDefinition;
import org.simplepoint.security.entity.Menu;
import org.simplepoint.security.entity.MenuAncestor;
import org.simplepoint.security.entity.Permissions;
import org.simplepoint.security.entity.TreeMenu;
import org.simplepoint.security.pojo.dto.MenuFeaturesRelevanceDto;
import org.simplepoint.security.pojo.dto.ServiceMenuResult;
import org.simplepoint.security.service.MenuService;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

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

    private final PermissionsService permissionsService;

    private final FeatureService featureService;

    private final MenuFeatureRelevanceRepository menuFeatureRelevanceRepository;

    private final DataInitializeExecutor dataInitializeManager;
    private final PermissionChangeLogRemoteService permissionChangeLogRemoteService;

    /**
     * Cached admin routes result with expiry timestamp.
     */
    private volatile ServiceMenuResult adminRoutesCache;
    private volatile long adminRoutesCacheExpiry;

    /**
     * Constructs a MenuServiceImpl with the specified dependencies.
     *
     * @param repository                     the MenuRepository for data access
     * @param detailsProviderService         the DetailsProviderService for user details retrieval
     * @param menuAncestorRepository         the MenuAncestorRepository for managing menu ancestor relationships
     * @param permissionsService             the PermissionsService for managing permissions
     * @param menuFeatureRelevanceRepository the MenuFeatureRelevanceRepository for managing menu-feature relationships
     * @param dataInitializeManager          the DataInitializeExecutor for handling data initialization tasks
     */
    public MenuServiceImpl(
            final MenuRepository repository,
            final DetailsProviderService detailsProviderService,
            final MenuAncestorRepository menuAncestorRepository,
            final PermissionsService permissionsService,
            final FeatureService featureService,
            final MenuFeatureRelevanceRepository menuFeatureRelevanceRepository,
            final DataInitializeExecutor dataInitializeManager,
            final PermissionChangeLogRemoteService permissionChangeLogRemoteService
    ) {
        super(repository, detailsProviderService);
        this.menuAncestorRepository = menuAncestorRepository;
        this.permissionsService = permissionsService;
        this.featureService = featureService;
        this.menuFeatureRelevanceRepository = menuFeatureRelevanceRepository;
        this.dataInitializeManager = dataInitializeManager;
        this.permissionChangeLogRemoteService = permissionChangeLogRemoteService;
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
            if (entity.getPath() != null && !entity.getPath().isEmpty()) {
                entity.setAuthority(entity.getPath().replace("/", "."));
            }
        }
        var saved = super.create(entity);
        adminRoutesCache = null;

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
            adminRoutesCache = null;
            var deleteIds = new HashSet<>(ids);
            // 顺便删除子菜单
            Collection<String> child = menuAncestorRepository.findChildIdsByAncestorIds(ids);
            deleteIds.addAll(child);
            // 删除菜单关联的权限
            menuFeatureRelevanceRepository.deleteAllByMenuIds(deleteIds);
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
    public void sync(String serviceName, Set<MenuChildren> data) {
        log.info("Menu sync started for service: {}", serviceName);
        adminRoutesCache = null;
        dataInitializeManager.execute("menu-[" + serviceName + "]-permission-initialize", () -> initializeMenusAndPermissions(data));
        dataInitializeManager.execute("menu-[" + serviceName + "]feature-initialize", () -> initializeFeaturesAndRelations(data));
        synchronizePermissionTypes(data);
        log.info("Menu sync completed for service: {}", serviceName);
    }

    private void initializeMenusAndPermissions(Set<MenuChildren> data) {
        var blockingQueue = new ArrayDeque<>(data);
        while (!blockingQueue.isEmpty()) {
            MenuChildren current = blockingQueue.poll();
            Menu currentMenu = this.create(current.toMenu());
            Set<Permissions> permissions = current.getPermissions();
            if (permissions != null && !permissions.isEmpty()) {
                permissionsService.create(permissions);
                permissionsService.flush();
            }
            Set<MenuChildren> children = current.getChildren();
            if (children != null && !children.isEmpty()) {
                for (MenuChildren child : children) {
                    child.setParent(currentMenu.getId());
                    blockingQueue.offer(child);
                }
            }
        }
    }

    private void initializeFeaturesAndRelations(Set<MenuChildren> data) {
        Collection<Menu> initializedMenus = getRepository().loadAll();
        Map<String, Menu> menusByAuthority = initializedMenus.stream()
                .filter(menu -> menu.getAuthority() != null && !menu.getAuthority().isBlank())
                .collect(Collectors.toMap(Menu::getAuthority, menu -> menu, (left, right) -> left, HashMap::new));
        Map<String, Menu> menusByPath = initializedMenus.stream()
                .filter(menu -> menu.getPath() != null && !menu.getPath().isBlank())
                .collect(Collectors.toMap(Menu::getPath, menu -> menu, (left, right) -> left, HashMap::new));

        var blockingQueue = new ArrayDeque<>(data);
        while (!blockingQueue.isEmpty()) {
            MenuChildren current = blockingQueue.poll();
            Map<String, Feature> featureDefinitions = resolveFeatureDefinitions(current);
            upsertFeatures(featureDefinitions.values());
            initializeFeaturePermissionRelations(featureDefinitions.keySet(), current.getPermissions());

            Set<String> featureCodes = resolveFeatureCodes(current.getFeatureCodes(), featureDefinitions.keySet());
            Menu menu = resolveMenu(menusByAuthority, menusByPath, current);
            if (menu != null && !featureCodes.isEmpty()) {
                initializeMenuFeatureRelations(menu.getId(), featureCodes);
            }

            Set<MenuChildren> children = current.getChildren();
            if (children != null && !children.isEmpty()) {
                blockingQueue.addAll(children);
            }
        }
    }

    private void synchronizePermissionTypes(Set<MenuChildren> data) {
        Map<String, Integer> configuredTypes = new LinkedHashMap<>();
        collectPermissionTypes(data, configuredTypes);
        if (configuredTypes.isEmpty()) {
            return;
        }

        List<Permissions> updates = permissionsService.findAll(Map.of()).stream()
                .filter(permission -> permission.getAuthority() != null && configuredTypes.containsKey(permission.getAuthority()))
                .filter(permission -> !Objects.equals(permission.getType(), configuredTypes.get(permission.getAuthority())))
                .map(permission -> {
                    Permissions update = new Permissions();
                    BeanUtils.copyProperties(permission, update);
                    update.setType(configuredTypes.get(permission.getAuthority()));
                    return update;
                })
                .toList();

        if (updates.isEmpty()) {
            return;
        }

        log.info("Synchronizing permission types for {} permissions from menu configuration", updates.size());
        for (Permissions update : updates) {
            permissionsService.modifyById(update);
        }
        permissionsService.flush();
    }

    private void collectPermissionTypes(Set<MenuChildren> menus, Map<String, Integer> configuredTypes) {
        if (menus == null || menus.isEmpty()) {
            return;
        }
        for (MenuChildren menu : menus) {
            Set<Permissions> permissions = menu.getPermissions();
            if (permissions != null && !permissions.isEmpty()) {
                for (Permissions permission : permissions) {
                    if (permission.getAuthority() == null || permission.getAuthority().isBlank()) {
                        continue;
                    }
                    configuredTypes.put(permission.getAuthority(), permission.getType());
                }
            }
            collectPermissionTypes(menu.getChildren(), configuredTypes);
        }
    }

    private Map<String, Feature> resolveFeatureDefinitions(MenuChildren current) {
        Map<String, Feature> definitions = new LinkedHashMap<>();
        Set<Permissions> permissions = current.getPermissions();
        if (permissions == null || permissions.isEmpty()) {
            return definitions;
        }
        for (Permissions permission : permissions) {
            MenuFeatureDefinition definition = permission.getFeature();
            if (definition == null || definition.getCode() == null || definition.getCode().isBlank()) {
                continue;
            }
            Feature feature = new Feature();
            feature.setPublicAccess(definition.getPublicAccess() != null ? definition.getPublicAccess() : Boolean.FALSE);
            feature.setCode(definition.getCode());
            feature.setName(definition.getName());
            feature.setDescription(definition.getDescription());
            feature.setSort(current.getSort());

            Feature existing = definitions.putIfAbsent(feature.getCode(), feature);
            if (existing != null && (!Objects.equals(existing.getName(), feature.getName())
                    || !Objects.equals(existing.getDescription(), feature.getDescription())
                    || !Objects.equals(existing.getSort(), feature.getSort()))) {
                log.warn("Conflicting feature metadata found for code: {}, using the first definition", feature.getCode());
            }
        }
        return definitions;
    }

    private void upsertFeatures(Collection<Feature> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return;
        }

        Map<String, Feature> existingByCode = featureService.findAll(Map.of()).stream()
                .filter(feature -> feature.getCode() != null && !feature.getCode().isBlank())
                .collect(Collectors.toMap(Feature::getCode, feature -> feature, (left, right) -> left, HashMap::new));

        List<Feature> toCreate = new ArrayList<>();
        for (Feature definition : definitions) {
            Feature existing = existingByCode.get(definition.getCode());
            if (existing == null) {
                toCreate.add(definition);
                continue;
            }
            if (Objects.equals(existing.getName(), definition.getName())
                    && Objects.equals(existing.getDescription(), definition.getDescription())
                    && Objects.equals(existing.getSort(), definition.getSort())) {
                continue;
            }
            Feature update = new Feature();
            update.setId(existing.getId());
            update.setCode(definition.getCode());
            update.setName(definition.getName());
            update.setDescription(definition.getDescription());
            update.setSort(definition.getSort());
            featureService.modifyById(update);
        }

        if (!toCreate.isEmpty()) {
            featureService.create(new ArrayList<>(toCreate));
            featureService.flush();
        }
    }

    private void initializeFeaturePermissionRelations(Set<String> featureCodes, Set<Permissions> permissions) {
        if (featureCodes == null || featureCodes.isEmpty() || permissions == null || permissions.isEmpty()) {
            return;
        }

        Set<String> permissionAuthorities = permissions.stream()
                .map(Permissions::getAuthority)
                .filter(authority -> authority != null && !authority.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (permissionAuthorities.isEmpty()) {
            return;
        }

        for (String featureCode : featureCodes) {
            Set<String> missingAuthorities = new LinkedHashSet<>(permissionAuthorities);
            missingAuthorities.removeAll(new HashSet<>(featureService.authorizedPermissions(featureCode)));
            if (missingAuthorities.isEmpty()) {
                continue;
            }
            FeaturePermissionsRelevanceDto dto = new FeaturePermissionsRelevanceDto();
            dto.setFeatureCode(featureCode);
            dto.setPermissionAuthority(missingAuthorities);
            featureService.authorizePermissions(dto);
        }
    }

    private void initializeMenuFeatureRelations(String menuId, Set<String> featureCodes) {
        if (menuId == null || menuId.isBlank() || featureCodes == null || featureCodes.isEmpty()) {
            return;
        }
        Set<String> missingFeatureCodes = new LinkedHashSet<>(featureCodes);
        missingFeatureCodes.removeAll(new HashSet<>(this.authorized(menuId)));
        if (missingFeatureCodes.isEmpty()) {
            return;
        }
        this.authorize(new MenuFeaturesRelevanceDto(menuId, missingFeatureCodes));
    }

    private Set<String> resolveFeatureCodes(Set<String> configuredFeatureCodes, Set<String> featureCodesFromPermissions) {
        Set<String> featureCodes = new LinkedHashSet<>();
        if (configuredFeatureCodes != null) {
            featureCodes.addAll(configuredFeatureCodes.stream()
                    .filter(code -> code != null && !code.isBlank())
                    .collect(Collectors.toSet()));
        }
        if (featureCodesFromPermissions != null) {
            featureCodes.addAll(featureCodesFromPermissions.stream()
                    .filter(code -> code != null && !code.isBlank())
                    .collect(Collectors.toSet()));
        }
        return featureCodes;
    }

    private Menu resolveMenu(Map<String, Menu> menusByAuthority, Map<String, Menu> menusByPath, MenuChildren current) {
        if (current.getAuthority() != null && !current.getAuthority().isBlank()) {
            Menu menu = menusByAuthority.get(current.getAuthority());
            if (menu != null) {
                return menu;
            }
        }
        if (current.getPath() != null && !current.getPath().isBlank()) {
            return menusByPath.get(current.getPath());
        }
        return null;
    }

    /**
     * Retrieves the collection of menus accessible to the current user.
     *
     * @return a collection of {@link TreeMenu} entities available to the user
     */
    @Override
    public ServiceMenuResult routes() {
        var userContext = getAuthorizationContext();
        Set<ServiceMenuResult.ServiceEntry> services = new HashSet<>();
        if (userContext == null) {
            throw new IllegalArgumentException("User context is null or not logged in");
        }

        if (userContext.getIsAdministrator()) {
            ServiceMenuResult cached = adminRoutesCache;
            if (cached != null && System.currentTimeMillis() < adminRoutesCacheExpiry) {
                return cached;
            }
            Set<ServiceMenuResult.ServiceEntry> adminServices = new HashSet<>();
            ServiceMenuResult result = ServiceMenuResult.of(
                    adminServices,
                    buildMenuTree(getRepository().loadAll(), adminServices, true)
            );
            adminRoutesCache = result;
            adminRoutesCacheExpiry = System.currentTimeMillis() + 30_000; // 30s TTL
            return result;
        }

        Set<String> featureCodes = new HashSet<>(userContext.getPermissions());
        if (!featureCodes.isEmpty()) {
            // 查询全部已授权的菜单ID
            Set<String> authorizedMenuIds = new HashSet<>();
            // 兼容历史数据：若上下文中仍为旧的权限编码，则沿用旧表物理字段承载的绑定关系
            authorizedMenuIds.addAll(menuFeatureRelevanceRepository.findAllMenuIdByFeatureCodes(featureCodes));
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
        return this.menuFeatureRelevanceRepository.authorized(menuId);
    }

    /**
     * Authorizes the menu with the specified features based on the provided DTO.
     *
     * @param dto the data transfer object containing menu identifier and feature codes
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void authorize(MenuFeaturesRelevanceDto dto) {
        Set<String> featureCodes = dto.resolvedFeatureCodes();
        if (featureCodes == null || featureCodes.isEmpty()) {
            log.warn("authorize() skipped: no feature codes resolved for menuId={}", dto.getMenuId());
            return;
        }
        Set<MenuFeatureRelevance> saveAuthorities = new HashSet<>(featureCodes.size());
        for (String featureCode : featureCodes) {
            MenuFeatureRelevance relevance = new MenuFeatureRelevance();
            relevance.setMenuId(dto.getMenuId());
            relevance.setFeatureCode(featureCode);
            saveAuthorities.add(relevance);
        }
        menuFeatureRelevanceRepository.authorize(saveAuthorities);
        recordPermissionChange("AUTHORIZE", dto.getMenuId(), featureCodes);
    }

    /**
     * Revokes the authorization of the menu with the specified features based on the provided DTO.
     *
     * @param dto the data transfer object containing menu identifier and feature codes
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void unauthorized(MenuFeaturesRelevanceDto dto) {
        Set<String> featureCodes = dto.resolvedFeatureCodes();
        if (featureCodes == null || featureCodes.isEmpty()) {
            log.warn("unauthorized() skipped: no feature codes resolved for menuId={}", dto.getMenuId());
            return;
        }
        menuFeatureRelevanceRepository.unauthorized(dto.getMenuId(), featureCodes);
        recordPermissionChange("UNAUTHORIZE", dto.getMenuId(), featureCodes);
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
                if (parent.getChildren() == null) {
                    parent.setChildren(new ArrayList<>());
                }
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

    private void recordPermissionChange(String action, String menuId, Set<String> featureCodes) {
        AuthorizationContext authorizationContext = getAuthorizationContext();
        if (authorizationContext == null || authorizationContext.getUserId() == null || authorizationContext.getUserId().isBlank()) {
            return;
        }

        Set<String> normalizedFeatureCodes = featureCodes == null ? Set.of() : featureCodes;
        PermissionChangeLogRecordCommand command = new PermissionChangeLogRecordCommand();
        command.setChangedAt(java.time.Instant.now());
        command.setChangeType("MENU_FEATURE");
        command.setAction(action);
        command.setSubjectType("MENU");
        command.setSubjectId(menuId);
        command.setSubjectLabel(resolveMenuLabel(menuId));
        command.setTargetType("FEATURE");
        command.setTargetSummary(resolveFeatureSummary(normalizedFeatureCodes));
        command.setTargetCount(normalizedFeatureCodes.size());
        command.setOperatorId(authorizationContext.getUserId());
        command.setTenantId(resolveTenantScope());
        command.setContextId(authorizationContext.getContextId());
        command.setSourceService("common");
        command.setDescription(action + " MENU_FEATURE [" + command.getSubjectLabel() + "] -> [" + command.getTargetSummary() + "]");
        permissionChangeLogRemoteService.record(command);
    }

    private String resolveMenuLabel(String menuId) {
        return findById(menuId)
                .map(menu -> {
                    String label = firstNonBlank(menu.getLabel(), menu.getTitle(), menu.getAuthority(), menu.getPath(), menu.getId());
                    String path = menu.getPath();
                    if (path != null && !path.isBlank() && !Objects.equals(label, path)) {
                        return label + " [" + path + "]";
                    }
                    return label;
                })
                .orElse(menuId);
    }

    private String resolveFeatureSummary(Set<String> featureCodes) {
        if (featureCodes == null || featureCodes.isEmpty()) {
            return "";
        }
        LinkedHashSet<String> labels = featureService.findAllByCodes(featureCodes).stream()
                .map(feature -> firstNonBlank(feature.getCode(), feature.getName()))
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (labels.isEmpty()) {
            labels.addAll(featureCodes);
        }
        return String.join(",", labels);
    }

    private String resolveTenantScope() {
        String tenantId = currentTenantId();
        return tenantId == null || tenantId.isBlank() ? "default" : tenantId;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
