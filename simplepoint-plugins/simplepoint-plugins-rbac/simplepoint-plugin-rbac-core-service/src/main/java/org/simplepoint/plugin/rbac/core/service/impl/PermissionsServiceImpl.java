/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.service.impl;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeGuards;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.PermissionsRelevanceVo;
import org.simplepoint.plugin.rbac.core.api.repository.PermissionsRepository;
import org.simplepoint.plugin.rbac.core.api.repository.ResourcesRelevanceRepository;
import org.simplepoint.plugin.rbac.core.api.repository.RolePermissionsRelevanceRepository;
import org.simplepoint.plugin.rbac.core.api.service.PermissionsService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.repository.FeaturePermissionRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.PermissionVersionRefreshService;
import org.simplepoint.security.entity.Permissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PermissionsServiceImpl provides the implementation for PermissionsService.
 * It utilizes the repository to load permissions associated with a specific role authority.
 */
@Primary
@Service
public class PermissionsServiceImpl
    extends BaseServiceImpl<PermissionsRepository, Permissions, String>
    implements PermissionsService {

  private final RolePermissionsRelevanceRepository rolePermissionsRelevanceRepository;
  private final FeaturePermissionRelevanceRepository featurePermissionRelevanceRepository;
  private final TenantRepository tenantRepository;
  private final ResourcesRelevanceRepository resourcesRelevanceRepository;
  private final PermissionVersionRefreshService permissionVersionRefreshService;

  /**
     * Constructs a PermissionsServiceImpl with the specified repository, user context, and details provider service.
     *
     * @param repository             the PermissionsRepository for data access
     * @param detailsProviderService the DetailsProviderService for additional details
     */
  public PermissionsServiceImpl(
      PermissionsRepository repository,
      DetailsProviderService detailsProviderService,
      RolePermissionsRelevanceRepository rolePermissionsRelevanceRepository,
      @Autowired(required = false)
      FeaturePermissionRelevanceRepository featurePermissionRelevanceRepository,
      @Autowired(required = false)
      TenantRepository tenantRepository,
      ResourcesRelevanceRepository resourcesRelevanceRepository,
      PermissionVersionRefreshService permissionVersionRefreshService
  ) {
    super(repository, detailsProviderService);
    this.rolePermissionsRelevanceRepository = rolePermissionsRelevanceRepository;
    this.featurePermissionRelevanceRepository = featurePermissionRelevanceRepository;
    this.tenantRepository = tenantRepository;
    this.resourcesRelevanceRepository = resourcesRelevanceRepository;
    this.permissionVersionRefreshService = permissionVersionRefreshService;
  }

  @Override
  public <S extends Permissions> Page<S> limit(Map<String, String> attributes, Pageable pageable) {
    if (AuthorizationScopeGuards.isPlatformAdministrator(getAuthorizationContext())) {
      return super.limit(attributes, pageable);
    }
    Set<String> authorities = visiblePermissionAuthorities();
    if (authorities.isEmpty()) {
      return Page.empty(pageable);
    }
    Map<String, String> scopedAttributes = new HashMap<>(attributes == null ? Map.of() : attributes);
    scopedAttributes.put(Permissions.AUTHORITY_FIELD, "in:" + String.join(",", authorities));
    return super.limit(scopedAttributes, pageable);
  }

  @Override
  public Optional<Permissions> findById(String id) {
    Optional<Permissions> permission = super.findById(id);
    if (permission.isEmpty() || AuthorizationScopeGuards.isPlatformAdministrator(getAuthorizationContext())) {
      return permission;
    }
    return visiblePermissionAuthorities().contains(permission.get().getAuthority()) ? permission : Optional.empty();
  }

  /**
     * Retrieves a paginated list of permission items along with their associated roles.
     *
     * @param pageable the pagination information
     * @return a page of RolePermissionsRelevanceVo containing permission items and their roles
     */
  @Override
  public Page<PermissionsRelevanceVo> permissionItems(Pageable pageable) {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    if (context.getIsAdministrator()) {
      return getRepository().permissionItemsAll(pageable);
    }
    Set<String> authorities = visiblePermissionAuthorities();
    if (authorities.isEmpty()) {
      return Page.empty(pageable);
    }
    return getRepository().permissionItems(pageable, authorities);
  }

  @Override
  public Collection<PermissionsRelevanceVo> permissionItems(Collection<String> authorities) {
    if (authorities == null || authorities.isEmpty()) {
      return java.util.List.of();
    }
    if (AuthorizationScopeGuards.isPlatformAdministrator(getAuthorizationContext())) {
      return getRepository().permissionItems(authorities);
    }
    if (resolveTenantScopeOrNull() == null) {
      return getRepository().permissionItems(authorities);
    }
    Set<String> visibleAuthorities = visiblePermissionAuthorities();
    Set<String> scopedAuthorities = authorities.stream()
        .filter(visibleAuthorities::contains)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    if (scopedAuthorities.isEmpty()) {
      return java.util.List.of();
    }
    return getRepository().permissionItems(scopedAuthorities);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends Permissions> Permissions modifyById(S entity) {
    Permissions current = super.findById(entity.getId()).orElseThrow(() -> new IllegalArgumentException("权限不存在"));
    String oldAuthority = current.getAuthority();
    Set<String> affectedTenantIds = collectAffectedTenantIds(Set.of(oldAuthority));
    Permissions updated = (Permissions) super.modifyById(entity);
    if (!java.util.Objects.equals(oldAuthority, updated.getAuthority())) {
      rolePermissionsRelevanceRepository.updatePermissionAuthority(oldAuthority, updated.getAuthority());
      featurePermissionRelevanceRepository.updatePermissionAuthority(oldAuthority, updated.getAuthority());
      resourcesRelevanceRepository.updatePermissionAuthority(oldAuthority, updated.getAuthority());
      permissionVersionRefreshService.refreshTenants(affectedTenantIds);
    }
    return updated;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeByIds(Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    Set<String> authorities = findAllByIds(ids).stream()
        .map(Permissions::getAuthority)
        .filter(authority -> authority != null && !authority.isBlank())
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    if (authorities.isEmpty()) {
      super.removeByIds(ids);
      return;
    }
    rolePermissionsRelevanceRepository.deleteAllByPermissionAuthorities(authorities);
    featurePermissionRelevanceRepository.deleteAllByPermissionAuthorities(authorities);
    resourcesRelevanceRepository.deleteAllByPermissionAuthorities(authorities);
    Set<String> affectedTenantIds = collectAffectedTenantIds(authorities);
    super.removeByIds(ids);
    permissionVersionRefreshService.refreshTenants(affectedTenantIds);
  }

  private Set<String> collectAffectedTenantIds(Collection<String> authorities) {
    if (authorities == null || authorities.isEmpty()) {
      return Set.of();
    }
    return new LinkedHashSet<>(permissionVersionRefreshService.findAffectedTenantIdsByPermissionAuthorities(authorities));
  }

  private Set<String> visiblePermissionAuthorities() {
    String tenantId = resolveTenantScopeOrNull();
    if (featurePermissionRelevanceRepository != null && tenantId != null && !tenantId.isBlank()) {
      return featurePermissionRelevanceRepository.findPermissionAuthoritiesByTenantId(tenantId).stream()
          .filter(authority -> authority != null && !authority.isBlank())
          .collect(Collectors.toCollection(LinkedHashSet::new));
    }
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    Collection<String> contextPermissions = context == null ? Set.of() : context.getPermissions();
    return contextPermissions == null ? Set.of() : contextPermissions.stream()
        .filter(authority -> authority != null && !authority.isBlank())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private String resolveTenantScopeOrNull() {
    String tenantId = currentTenantId();
    if (tenantId != null && !tenantId.isBlank()) {
      return tenantId;
    }
    var ctx = getAuthorizationContext();
    String userId = ctx != null ? ctx.getUserId() : null;
    if (userId == null || userId.isBlank() || tenantRepository == null) {
      return null;
    }
    return tenantRepository.findPersonalTenantByOwnerId(userId)
        .map(Tenant::getId)
        .orElse(null);
  }
}
