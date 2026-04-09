/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.service.impl;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.auditing.logging.api.pojo.command.PermissionChangeLogRecordCommand;
import org.simplepoint.plugin.auditing.logging.api.service.PermissionChangeLogRemoteService;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.RolePermissionsRelevanceDto;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RoleRelevanceVo;
import org.simplepoint.plugin.rbac.core.api.repository.RolePermissionsRelevanceRepository;
import org.simplepoint.plugin.rbac.core.api.repository.RoleRepository;
import org.simplepoint.plugin.rbac.core.api.service.RoleService;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.RolePermissionsRelevance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service implementation class for managing Role entities in the RBAC (Role-Based Access Control) system.
 * This class extends the BaseServiceImpl to inherit common CRUD operations and implements the RolesService
 * interface for defining role-specific business logic.
 */
@Service
public class RolesServiceImpl extends BaseServiceImpl<RoleRepository, Role, String>
    implements RoleService {

  private final RolePermissionsRelevanceRepository rolePermissionsRelevanceRepository;
  private final TenantRepository tenantRepository;
  private final PermissionChangeLogRemoteService permissionChangeLogRemoteService;

  /**
   * Constructs a new RolesServiceImpl with the specified repository, user context, and details provider service.
   *
   * @param repository                         the RoleRepository instance for data access
   * @param detailsProviderService             the service for providing user details
   * @param rolePermissionsRelevanceRepository the RolePermissionsRelevanceRepository for role-permission relations
   */
  public RolesServiceImpl(
      RoleRepository repository,
      DetailsProviderService detailsProviderService,
      RolePermissionsRelevanceRepository rolePermissionsRelevanceRepository,
      @Autowired(required = false)
      TenantRepository tenantRepository,
      PermissionChangeLogRemoteService permissionChangeLogRemoteService
  ) {
    super(repository, detailsProviderService);
    this.rolePermissionsRelevanceRepository = rolePermissionsRelevanceRepository;
    this.tenantRepository = tenantRepository;
    this.permissionChangeLogRemoteService = permissionChangeLogRemoteService;
  }

  /**
   * Retrieves a paginated list of RoleSelectDto for role selection purposes.
   *
   * @param pageable Pagination information.
   * @return A page of RoleSelectDto containing role selection data.
   */
  @Override
  public Page<RoleRelevanceVo> roleSelectItems(Pageable pageable) {
    requireTenantOwnerOrAdministratorIfTenantScoped();
    return getRepository().roleSelectItems(resolveCurrentTenantScope(), pageable);
  }


  /**
   * Removes the authorization of specified permissions from a role.
   *
   * @param roleId        the authority of the role
   * @param permissionAuthority the set of permission permissionAuthority to be unauthorized
   */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public void unauthorized(String roleId, Set<String> permissionAuthority) {
    requireTenantOwnerOrAdministratorIfTenantScoped();
    validateRoleBelongsToCurrentTenant(roleId);
    String tenantId = resolveCurrentTenantScope();
    this.getRepository().unauthorized(tenantId, roleId, permissionAuthority);
    refreshCurrentTenantPermissionVersion();
    recordPermissionChange("UNAUTHORIZE", roleId, permissionAuthority);
  }

  /**
   * Retrieves a collection of authorized permission permissionAuthority for a given role authority.
   *
   * @param roleId the authority of the role
   * @return a collection of authorized permission permissionAuthority
   */
  @Override
  public Collection<String> authorized(String roleId) {
    requireTenantOwnerOrAdministratorIfTenantScoped();
    validateRoleBelongsToCurrentTenant(roleId);
    return getRepository().authorized(resolveCurrentTenantScope(), roleId);
  }

  /**
   * Authorizes a set of permissions to a role based on the provided DTO.
   *
   * @param dto the RolePermissionsRelevanceDto containing role authority and permission authorities
   * @return a collection of RolePermissionsRelevance representing the authorized permissions
   */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public Collection<RolePermissionsRelevance> authorize(RolePermissionsRelevanceDto dto) {
    requireTenantOwnerOrAdministratorIfTenantScoped();
    validateRoleBelongsToCurrentTenant(dto.getRoleId());
    Set<String> pms = dto.getPermissionAuthority();
    Set<RolePermissionsRelevance> rels = new HashSet<>();
    String roleId = dto.getRoleId();
    for (String pm : pms) {
      RolePermissionsRelevance relevance = new RolePermissionsRelevance();
      relevance.setRoleId(roleId);
      relevance.setPermissionAuthority(pm);
      applyCurrentTenantIdIfNecessary(relevance);
      rels.add(relevance);
    }
    Collection<RolePermissionsRelevance> saved = this.rolePermissionsRelevanceRepository.saveAll(rels);
    refreshCurrentTenantPermissionVersion();
    recordPermissionChange("AUTHORIZE", roleId, pms);
    return saved;
  }

  private void requireTenantOwnerOrAdministratorIfTenantScoped() {
    String tenantId = currentTenantId();
    if (tenantId == null || tenantId.isBlank() || "default".equals(tenantId)) {
      return;
    }
    if (Boolean.TRUE.equals(getAuthorizationContext().getIsAdministrator())) {
      return;
    }
    var tenant = tenantRepository.findById(tenantId)
        .orElseThrow(() -> new IllegalArgumentException("租户不存在"));
    if (!Objects.equals(tenant.getOwnerId(), getAuthorizationContext().getUserId())) {
      throw new AccessDeniedException("仅租户所有者可以配置当前租户角色权限");
    }
  }

  private String resolveCurrentTenantScope() {
    String tenantId = currentTenantId();
    return tenantId == null || tenantId.isBlank() ? "default" : tenantId;
  }

  private void validateRoleBelongsToCurrentTenant(String roleId) {
    String tenantId = resolveCurrentTenantScope();
    Role role = findById(roleId).orElseThrow(() -> new IllegalArgumentException("角色不存在"));
    if (!Objects.equals(role.getTenantId(), tenantId)) {
      throw new IllegalArgumentException("角色不存在或不属于当前租户");
    }
  }

  private void refreshCurrentTenantPermissionVersion() {
    String tenantId = currentTenantId();
    if (tenantId == null || tenantId.isBlank() || "default".equals(tenantId)) {
      return;
    }
    tenantRepository.increasePermissionVersion(Set.of(tenantId));
  }

  private void recordPermissionChange(String action, String roleId, Set<String> permissionAuthorities) {
    AuthorizationContext authorizationContext = getAuthorizationContext();
    if (authorizationContext == null || authorizationContext.getUserId() == null || authorizationContext.getUserId().isBlank()) {
      return;
    }

    Set<String> normalizedAuthorities = permissionAuthorities == null ? Set.of() : permissionAuthorities;
    PermissionChangeLogRecordCommand command = new PermissionChangeLogRecordCommand();
    command.setChangedAt(Instant.now());
    command.setChangeType("ROLE_PERMISSION");
    command.setAction(action);
    command.setSubjectType("ROLE");
    command.setSubjectId(roleId);
    command.setSubjectLabel(resolveRoleLabel(roleId));
    command.setTargetType("PERMISSION");
    command.setTargetSummary(joinValues(normalizedAuthorities));
    command.setTargetCount(normalizedAuthorities.size());
    command.setOperatorId(authorizationContext.getUserId());
    command.setTenantId(resolveCurrentTenantScope());
    command.setContextId(authorizationContext.getContextId());
    command.setSourceService("common");
    command.setDescription(action + " ROLE_PERMISSION [" + command.getSubjectLabel() + "] -> [" + command.getTargetSummary() + "]");
    permissionChangeLogRemoteService.record(command);
  }

  private String resolveRoleLabel(String roleId) {
    return findById(roleId)
        .map(role -> firstNonBlank(role.getAuthority(), role.getRoleName(), role.getId()))
        .orElse(roleId);
  }

  private String joinValues(Set<String> values) {
    if (values == null || values.isEmpty()) {
      return "";
    }
    return values.stream()
        .filter(value -> value != null && !value.isBlank())
        .collect(Collectors.toCollection(LinkedHashSet::new))
        .stream()
        .collect(Collectors.joining(","));
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
