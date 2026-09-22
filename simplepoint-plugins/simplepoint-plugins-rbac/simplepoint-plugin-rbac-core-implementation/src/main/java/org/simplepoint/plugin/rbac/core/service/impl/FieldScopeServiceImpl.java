/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.service.impl;

import java.util.Collection;
import java.util.List;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.rbac.core.api.repository.FieldScopeRepository;
import org.simplepoint.plugin.rbac.core.api.service.FieldScopeService;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.security.entity.FieldScope;
import org.simplepoint.security.entity.FieldScopeEntry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service implementation for managing {@link FieldScope} entities.
 * Invalidates the tenant authorization cache on every mutation so that
 * AuthorizationContext rebuild picks up the updated scope immediately.
 */
@Service
public class FieldScopeServiceImpl extends BaseServiceImpl<FieldScopeRepository, FieldScope, String>
    implements FieldScopeService {

  private final ResourceAuthorizationVersionService resourceAuthorizationVersionService;
  private final FieldScopeCatalog fieldCatalog;
  private final PermissionPolicyGuard policyGuard;

  /**
   * Field Scope Service Impl.
   */
  public FieldScopeServiceImpl(
      FieldScopeRepository repository,
      DetailsProviderService detailsProviderService,
      ResourceAuthorizationVersionService resourceAuthorizationVersionService,
      FieldScopeCatalog fieldCatalog, PermissionPolicyGuard policyGuard
  ) {
    super(repository, detailsProviderService);
    this.resourceAuthorizationVersionService = resourceAuthorizationVersionService;
    this.fieldCatalog = fieldCatalog;
    this.policyGuard = policyGuard;
  }

  @Override
  public java.util.Map<String, List<String>> catalog() {
    requireCurrentTenantId();
    return fieldCatalog.entries();
  }

  @Override
  public java.util.Map<String, PageFields> pageCatalog() {
    requireCurrentTenantId();
    return fieldCatalog.pages();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public FieldScope createConfigured(Configuration configuration) {
    var entries = configuredEntries(configuration, List.of());
    FieldScope scope = new FieldScope();
    scope.setName(configuration.name());
    scope.setDescription(configuration.description());
    FieldScope created = create(scope);
    return replaceEntries(created.getId(), entries);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public FieldScope updateConfigured(String id, Configuration configuration) {
    var existing = getRepository().findByIdAndTenantIdAndDeletedAtIsNull(id, requireCurrentTenantId())
        .orElseThrow(() -> new org.simplepoint.core.permission.InvalidPermissionPolicyException("字段规则不存在"));
    var entries = configuredEntries(configuration, existing.getEntries());
    FieldScope metadata = new FieldScope();
    metadata.setId(id);
    metadata.setName(configuration.name());
    metadata.setDescription(configuration.description());
    modifyById(metadata);
    return replaceEntries(id, entries);
  }

  private List<FieldScopeEntry> configuredEntries(Configuration configuration, List<FieldScopeEntry> existing) {
    if (configuration == null || configuration.entries() == null || configuration.entries().size() > 500) {
      throw new org.simplepoint.core.permission.InvalidPermissionPolicyException("字段规则不能为空且不能超过 500 条");
    }
    var allowed = new java.util.HashSet<String>();
    var seen = new java.util.HashSet<String>();
    pageCatalog().values().forEach(page -> page.models().forEach(model -> model.properties().keySet()
        .forEach(field -> allowed.add(model.model() + "#" + field))));
    for (var entry : configuration.entries()) {
      if (entry == null || entry.getResource() == null || entry.getField() == null || entry.getAccess() == null) {
        throw new org.simplepoint.core.permission.InvalidPermissionPolicyException("资源、字段和访问级别不能为空");
      }
      if (!seen.add(entry.getResource() + "#" + entry.getField())) {
        throw new org.simplepoint.core.permission.InvalidPermissionPolicyException("同一资源字段不能重复配置");
      }
      boolean unchanged = existing.stream().anyMatch(old -> old.getResource().equals(entry.getResource())
          && old.getField().equals(entry.getField()) && old.getAccess() == entry.getAccess());
      if (!unchanged && !allowed.contains(entry.getResource() + "#" + entry.getField())) {
        throw new org.simplepoint.core.permission.InvalidPermissionPolicyException("字段未注册或所属页面未授权");
      }
    }
    return configuration.entries();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public FieldScope replaceEntries(String fieldScopeId, Collection<FieldScopeEntry> entries) {
    String tenantId = requireCurrentTenantId();
    FieldScope fieldScope = getRepository().findByIdAndTenantIdAndDeletedAtIsNull(fieldScopeId, tenantId)
        .orElseThrow(() -> new IllegalArgumentException("FieldScope not found: " + fieldScopeId));
    if (entries == null || entries.size() > 500) throw new org.simplepoint.core.permission.InvalidPermissionPolicyException("字段规则不能为空且不能超过 500 条");
    java.util.Map<String, FieldScopeEntry> validated = new java.util.LinkedHashMap<>();
    var catalog = fieldCatalog.entries();
    for (FieldScopeEntry entry : entries) {
      if (entry == null || entry.getResource() == null || entry.getField() == null || entry.getAccess() == null) {
        throw new org.simplepoint.core.permission.InvalidPermissionPolicyException("资源、字段和访问级别不能为空");
      }
      String field = entry.getField().trim();
      boolean unchanged = fieldScope.getEntries().stream().anyMatch(old -> old.getResource().equals(entry.getResource())
          && old.getField().equals(field) && old.getAccess() == entry.getAccess());
      String resource = unchanged ? entry.getResource() : fieldCatalog.validateResource(catalog, entry.getResource().trim(), field);
      FieldScopeEntry clean = new FieldScopeEntry();
      clean.setResource(resource);
      clean.setField(field);
      clean.setAccess(entry.getAccess());
      clean.setFieldScopeId(fieldScopeId);
      clean.setTenantId(tenantId);
      if (validated.putIfAbsent(resource + "#" + field, clean) != null) {
        throw new org.simplepoint.core.permission.InvalidPermissionPolicyException("字段规则重复: " + resource + "#" + field);
      }
    }
    // Reuse existing rows to avoid INSERT-before-orphan-DELETE unique-key collisions.
    fieldScope.getEntries().removeIf(existing -> {
      FieldScopeEntry replacement = validated.remove(existing.getResource() + "#" + existing.getField());
      if (replacement == null) return true;
      existing.setAccess(replacement.getAccess());
      return false;
    });
    fieldScope.getEntries().addAll(validated.values());
    FieldScope result = getRepository().save(fieldScope);
    refreshCurrentTenantAuthorizationVersion();
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends FieldScope> S create(S entity) {
    requireSeparateEntriesEndpoint(entity);
    entity.setTenantId(requireCurrentTenantId());
    S result = super.create(entity);
    refreshCurrentTenantAuthorizationVersion();
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<FieldScope> create(Collection<FieldScope> entities) {
    String tenantId = requireCurrentTenantId();
    entities.forEach(entity -> {
      requireSeparateEntriesEndpoint(entity);
      entity.setTenantId(tenantId);
    });
    List<FieldScope> result = super.create(entities);
    refreshCurrentTenantAuthorizationVersion();
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends FieldScope> FieldScope modifyById(S entity) {
    requireSeparateEntriesEndpoint(entity);
    entity.setTenantId(requireCurrentTenantId());
    FieldScope existing = getRepository().findByIdAndTenantIdAndDeletedAtIsNull(entity.getId(), currentTenantId())
        .orElseThrow(() -> new IllegalArgumentException("FieldScope not found"));
    entity.setEntries(existing.getEntries());
    FieldScope result = super.modifyById(entity);
    refreshCurrentTenantAuthorizationVersion();
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeByIds(Collection<String> ids) {
    policyGuard.requireUnused(requireCurrentTenantId(), ids, false);
    super.removeByIds(ids);
    refreshCurrentTenantAuthorizationVersion();
  }

  private void refreshCurrentTenantAuthorizationVersion() {
    String tenantId = currentTenantId();
    resourceAuthorizationVersionService.refreshTenant(tenantId);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeById(String id) {
    removeByIds(List.of(id));
  }

  @Override
  public void removeAll() {
    throw new org.springframework.security.access.AccessDeniedException("请按策略标识删除，不支持批量清空权限策略");
  }

  private void requireSeparateEntriesEndpoint(FieldScope entity) {
    if (entity == null) throw new org.simplepoint.core.permission.InvalidPermissionPolicyException("规则不能为空");
    entity.setName(PermissionPolicyGuard.name(entity.getName()));
    if (entity.getDescription() != null && entity.getDescription().length() > 200) {
      throw new org.simplepoint.core.permission.InvalidPermissionPolicyException("规则描述不能超过 200 个字符");
    }
    if (entity.getEntries() != null && !entity.getEntries().isEmpty()) {
      throw new org.simplepoint.core.permission.InvalidPermissionPolicyException("请使用字段规则配置接口修改 entries");
    }
  }

  private String requireCurrentTenantId() {
    String tenantId = currentTenantId();
    if (tenantId == null || tenantId.isBlank()) {
      throw new IllegalStateException("Tenant context is required");
    }
    return tenantId;
  }

  @Override
  protected boolean isDataScopeApplicable() {
    return false;
  }
}
