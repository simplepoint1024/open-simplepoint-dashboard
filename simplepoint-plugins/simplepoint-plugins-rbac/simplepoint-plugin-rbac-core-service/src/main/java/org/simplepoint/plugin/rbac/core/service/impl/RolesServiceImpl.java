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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.auditing.logging.api.pojo.command.ResourceGrantLogRecordCommand;
import org.simplepoint.plugin.auditing.logging.api.service.ResourceGrantLogRemoteService;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.RoleResourceGrantDto;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RoleRelevanceVo;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RoleScopeAssignmentVo;
import org.simplepoint.plugin.rbac.core.api.repository.RoleResourceGrantRepository;
import org.simplepoint.plugin.rbac.core.api.repository.RoleRepository;
import org.simplepoint.plugin.rbac.core.api.service.RoleService;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.RoleResourceGrant;
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
@Slf4j
@Service
public class RolesServiceImpl extends BaseServiceImpl<RoleRepository, Role, String>
    implements RoleService {

  private final RoleResourceGrantRepository roleResourceGrantRepository;
  private final TenantRepository tenantRepository;
  private final ResourceAuthorizationVersionService resourceAuthorizationVersionService;
  private final ResourceGrantLogRemoteService resourceGrantLogRemoteService;

  /**
   * Constructs a new RolesServiceImpl with the specified repository, user context, and details provider service.
   *
   * @param repository                         the RoleRepository instance for data access
   * @param detailsProviderService             the service for providing user details
   * @param roleResourceGrantRepository the repository for role-resource grants
   */
  public RolesServiceImpl(
      RoleRepository repository,
      DetailsProviderService detailsProviderService,
      RoleResourceGrantRepository roleResourceGrantRepository,
      @Autowired(required = false)
      TenantRepository tenantRepository,
      ResourceAuthorizationVersionService resourceAuthorizationVersionService,
      ResourceGrantLogRemoteService resourceGrantLogRemoteService
  ) {
    super(repository, detailsProviderService);
    this.roleResourceGrantRepository = roleResourceGrantRepository;
    this.tenantRepository = tenantRepository;
    this.resourceAuthorizationVersionService = resourceAuthorizationVersionService;
    this.resourceGrantLogRemoteService = resourceGrantLogRemoteService;
  }

  /**
   * Retrieves a paginated list of RoleSelectDto for role selection purposes.
   *
   * @param pageable Pagination information.
   * @return A page of RoleSelectDto containing role selection data.
   */
  @Override
  public <S extends Role> Page<S> limit(Map<String, String> attributes, Pageable pageable) {
    if (!Boolean.TRUE.equals(getAuthorizationContext().getIsAdministrator())) {
      ensureTenantContextAttribute(resolveCurrentTenantScope());
    }
    return super.limit(attributes, pageable);
  }

  @Override
  public Page<RoleRelevanceVo> roleSelectItems(Pageable pageable) {
    requireTenantOwnerOrAdministratorIfTenantScoped();
    return getRepository().roleSelectItems(resolveCurrentTenantScope(), pageable);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends Role> S create(S entity) {
    if (!Boolean.TRUE.equals(getAuthorizationContext().getIsAdministrator())) {
      ensureTenantContextAttribute(resolveCurrentTenantScope());
    }
    S saved = super.create(entity);
    refreshAuthorizationVersionByRoleTenantIds(List.of(saved));
    return saved;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<Role> create(Collection<Role> entities) {
    if (!Boolean.TRUE.equals(getAuthorizationContext().getIsAdministrator())) {
      ensureTenantContextAttribute(resolveCurrentTenantScope());
    }
    List<Role> saved = super.create(entities);
    refreshAuthorizationVersionByRoleTenantIds(saved);
    return saved;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends Role> Role modifyById(S entity) {
    if (!Boolean.TRUE.equals(getAuthorizationContext().getIsAdministrator())) {
      ensureTenantContextAttribute(resolveCurrentTenantScope());
      validateRoleBelongsToCurrentTenant(entity.getId());
    }
    Role updated = (Role) super.modifyById(entity);
    refreshAuthorizationVersionByRoleTenantIds(List.of(updated));
    return updated;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeByIds(Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    if (!Boolean.TRUE.equals(getAuthorizationContext().getIsAdministrator())) {
      ensureTenantContextAttribute(resolveCurrentTenantScope());
      ids.forEach(this::validateRoleBelongsToCurrentTenant);
    }
    Collection<Role> roles = findAllByIds(ids);
    super.removeByIds(ids);
    refreshAuthorizationVersionByRoleTenantIds(roles);
  }


  /**
   * Removes the authorization of specified resources from a role.
   *
   * @param roleId the role id
   * @param resourceCodes resource codes to revoke
   */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public void unauthorized(String roleId, Set<String> resourceCodes) {
    requireTenantOwnerOrAdministratorIfTenantScoped();
    validateRoleBelongsToCurrentTenant(roleId);
    String tenantId = resolveCurrentTenantScope();
    this.getRepository().unauthorized(tenantId, roleId, resourceCodes);
    refreshCurrentTenantAuthorizationVersion();
    recordResourceGrantChange("UNAUTHORIZE", roleId, resourceCodes);
  }

  /**
   * Retrieves resources granted to a role.
   *
   * @param roleId the role id
   * @return resource codes granted to the role
   */
  @Override
  public Collection<String> authorized(String roleId) {
    requireTenantOwnerOrAdministratorIfTenantScoped();
    validateRoleBelongsToCurrentTenant(roleId);
    return getRepository().authorized(resolveCurrentTenantScope(), roleId);
  }

  /**
   * Grants a set of resources to a role based on the provided DTO.
   *
   * @param dto role resource grant DTO
   * @return saved grants
   */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public Collection<RoleResourceGrant> authorize(RoleResourceGrantDto dto) {
    requireTenantOwnerOrAdministratorIfTenantScoped();
    validateRoleBelongsToCurrentTenant(dto.getRoleId());
    Set<String> resourceCodes = dto.getResourceCodes();
    Set<RoleResourceGrant> grants = new HashSet<>();
    String roleId = dto.getRoleId();
    for (String resourceCode : resourceCodes) {
      RoleResourceGrant grant = new RoleResourceGrant();
      grant.setRoleId(roleId);
      grant.setResourceCode(resourceCode);
      grant.setDataScopeId(dto.getDataScopeId());
      grant.setFieldScopeId(dto.getFieldScopeId());
      applyCurrentTenantIdIfNecessary(grant);
      grants.add(grant);
    }
    Collection<RoleResourceGrant> saved = this.roleResourceGrantRepository.saveAll(grants);
    refreshCurrentTenantAuthorizationVersion();
    recordResourceGrantChange("AUTHORIZE", roleId, resourceCodes);
    return saved;
  }

  private void requireTenantOwnerOrAdministratorIfTenantScoped() {
    String tenantId = resolveCurrentTenantScope();
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
    if (tenantId != null && !tenantId.isBlank()) {
      return tenantId;
    }
    var ctx = getAuthorizationContext();
    String userId = ctx != null ? ctx.getUserId() : null;
    if (userId == null || userId.isBlank()) {
      throw new IllegalStateException("Tenant context is required");
    }
    if (tenantRepository == null) {
      throw new IllegalStateException("Tenant repository is required to resolve tenant context");
    }
    return tenantRepository.findPersonalTenantByOwnerId(userId)
        .map(org.simplepoint.plugin.rbac.tenant.api.entity.Tenant::getId)
        .orElseThrow(() -> new IllegalStateException("Tenant context is required"));
  }

  private void ensureTenantContextAttribute(String tenantId) {
    if (tenantId == null || tenantId.isBlank()) {
      return;
    }
    AuthorizationContext context = getAuthorizationContext();
    if (context != null && (context.getAttribute("X-Tenant-Id") == null || context.getAttribute("X-Tenant-Id").isBlank())) {
      context.mergeAttributes(Map.of("X-Tenant-Id", tenantId));
    }
  }

  private void validateRoleBelongsToCurrentTenant(String roleId) {
    String tenantId = resolveCurrentTenantScope();
    Role role = findById(roleId).orElseThrow(() -> new IllegalArgumentException("角色不存在"));
    if (!Objects.equals(role.getTenantId(), tenantId)) {
      throw new IllegalArgumentException("角色不存在或不属于当前租户");
    }
  }

  private void refreshCurrentTenantAuthorizationVersion() {
    String tenantId = resolveCurrentTenantScope();
    if (tenantId == null || tenantId.isBlank()) {
      return;
    }
    resourceAuthorizationVersionService.refreshTenant(tenantId);
  }

  private void refreshAuthorizationVersionByRoleTenantIds(Collection<Role> roles) {
    if (roles == null || roles.isEmpty()) {
      return;
    }
    Set<String> tenantIds = roles.stream()
        .map(Role::getTenantId)
        .filter(tenantId -> tenantId != null && !tenantId.isBlank())
        .collect(Collectors.toSet());
    if (!tenantIds.isEmpty()) {
      resourceAuthorizationVersionService.refreshTenants(tenantIds);
    }
  }

  private void recordResourceGrantChange(String action, String roleId, Set<String> resourceCodes) {
    AuthorizationContext authorizationContext = getAuthorizationContext();
    if (authorizationContext == null || authorizationContext.getUserId() == null || authorizationContext.getUserId().isBlank()) {
      return;
    }

    Set<String> normalizedResources = resourceCodes == null ? Set.of() : resourceCodes;
    ResourceGrantLogRecordCommand command = new ResourceGrantLogRecordCommand();
    command.setChangedAt(Instant.now());
    command.setChangeType("ROLE_RESOURCE");
    command.setAction(action);
    command.setSubjectType("ROLE");
    command.setSubjectId(roleId);
    command.setSubjectLabel(resolveRoleLabel(roleId));
    command.setTargetType("RESOURCE");
    command.setTargetSummary(joinValues(normalizedResources));
    command.setTargetCount(normalizedResources.size());
    command.setOperatorId(authorizationContext.getUserId());
    command.setTenantId(resolveCurrentTenantScope());
    command.setContextId(authorizationContext.getContextId());
    command.setSourceService("common");
    command.setDescription(action + " ROLE_RESOURCE [" + command.getSubjectLabel() + "] -> [" + command.getTargetSummary() + "]");
    try {
      resourceGrantLogRemoteService.record(command);
    } catch (RuntimeException ex) {
      log.warn(
          "Failed to record role resource change log: action={}, roleId={}",
          action,
          roleId,
          ex
      );
    }
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

  @Override
  public RoleScopeAssignmentVo getScopeAssignment(String roleId) {
    String tenantId = resolveCurrentTenantScope();
    RoleScopeAssignmentVo vo = new RoleScopeAssignmentVo();
    vo.setRoleId(roleId);
    roleResourceGrantRepository.findFirstByTenantIdAndRoleId(tenantId, roleId)
        .ifPresent(record -> {
          vo.setDataScopeId(record.getDataScopeId());
          vo.setFieldScopeId(record.getFieldScopeId());
        });
    return vo;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void updateScopeAssignment(RoleScopeAssignmentVo vo) {
    requireTenantOwnerOrAdministratorIfTenantScoped();
    validateRoleBelongsToCurrentTenant(vo.getRoleId());
    String tenantId = resolveCurrentTenantScope();
    roleResourceGrantRepository.updateScopeForRole(
        tenantId, vo.getRoleId(), vo.getDataScopeId(), vo.getFieldScopeId());
    refreshCurrentTenantAuthorizationVersion();
  }
}
