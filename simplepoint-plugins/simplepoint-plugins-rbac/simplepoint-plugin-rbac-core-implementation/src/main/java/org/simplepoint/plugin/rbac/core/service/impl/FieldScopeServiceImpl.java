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

  @Override public org.springframework.data.domain.Page<ResourceNode> resourceNodes(String parentId, org.springframework.data.domain.Pageable pageable) {
    requireCurrentTenantId(); return fieldCatalog.nodes(parentId, pageable);
  }

  @Override public PageFields pageFields(String code) {
    requireCurrentTenantId();
    var page = fieldCatalog.pages(code).get(code);
    if (page == null) throw new org.simplepoint.core.permission.InvalidPermissionPolicyException("资源字段不可用");
    return page;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public FieldScope createConfigured(Configuration configuration) {
    var entries = configuredEntries(configuration, List.of());
    FieldScope scope = new FieldScope();
    scope.setName(configuration.name());
    scope.setDescription(configuration.description());
    FieldScope created = create(scope);
    // Base creation clears protected collection fields; a new policy has no pre-existing rows to preserve.
    if (created.getEntries() == null) created.setEntries(new java.util.ArrayList<>());
    return applyValidatedEntries(created, entries);
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
    var updated = modifyById(metadata);
    return applyValidatedEntries(updated, entries);
  }

  private List<FieldScopeEntry> configuredEntries(Configuration configuration, List<FieldScopeEntry> existing) {
    if (configuration == null || configuration.entries() == null || configuration.entries().size() > 500) {
      throw new org.simplepoint.core.permission.InvalidPermissionPolicyException("字段规则不能为空且不能超过 500 条");
    }
    assertEntriesWritable(existing, configuration.entries());
    var changed = new java.util.LinkedHashMap<String, java.util.Set<String>>();
    var seen = new java.util.HashSet<String>();
    var retained = entryValues(existing);
    for (var entry : configuration.entries()) {
      if (entry == null || entry.getResource() == null || entry.getField() == null || entry.getAccess() == null) {
        throw new org.simplepoint.core.permission.InvalidPermissionPolicyException("资源、字段和访问级别不能为空");
      }
      if (!seen.add(entry.getResource() + "#" + entry.getField())) {
        throw new org.simplepoint.core.permission.InvalidPermissionPolicyException("同一资源字段不能重复配置");
      }
      boolean unchanged = retained.get(entry.getResource() + "#" + entry.getField()) == entry.getAccess();
      if (!unchanged) changed.computeIfAbsent(entry.getResource(), ignored -> new java.util.LinkedHashSet<>()).add(entry.getField());
    }
    fieldCatalog.requireAuthorizedFields(changed);
    return configuration.entries();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public FieldScope replaceEntries(String fieldScopeId, Collection<FieldScopeEntry> entries) {
    String tenantId = requireCurrentTenantId();
    FieldScope fieldScope = getRepository().findByIdAndTenantIdAndDeletedAtIsNull(fieldScopeId, tenantId)
        .orElseThrow(() -> new IllegalArgumentException("FieldScope not found: " + fieldScopeId));
    if (entries == null || entries.size() > 500) throw new org.simplepoint.core.permission.InvalidPermissionPolicyException("字段规则不能为空且不能超过 500 条");
    assertEntriesWritable(fieldScope.getEntries(), entries);
    var changed = new java.util.LinkedHashMap<String, java.util.Set<String>>();
    var retained = entryValues(fieldScope.getEntries());
    for (FieldScopeEntry entry : entries) {
      if (entry == null || entry.getResource() == null || entry.getField() == null || entry.getAccess() == null) {
        throw new org.simplepoint.core.permission.InvalidPermissionPolicyException("资源、字段和访问级别不能为空");
      }
      String field = entry.getField().trim();
      boolean unchanged = retained.get(entry.getResource() + "#" + field) == entry.getAccess();
      if (!unchanged) changed.computeIfAbsent(entry.getResource().trim(), ignored -> new java.util.LinkedHashSet<>()).add(field);
    }
    var canonical = fieldCatalog.resolveLegacyFields(changed);
    var cleanEntries = new java.util.ArrayList<FieldScopeEntry>();
    for (var entry : entries) {
      String field = entry.getField().trim();
      boolean unchanged = retained.get(entry.getResource() + "#" + field) == entry.getAccess();
      String resource = unchanged ? entry.getResource() : canonical.get(entry.getResource().trim());
      FieldScopeEntry clean = new FieldScopeEntry();
      clean.setResource(resource);
      clean.setField(field);
      clean.setAccess(entry.getAccess());
      cleanEntries.add(clean);
    }
    return applyValidatedEntries(fieldScope, cleanEntries);
  }

  /** Only reached after the endpoint-specific validation. Never revalidate configured entries against the legacy registry. */
  private FieldScope applyValidatedEntries(FieldScope fieldScope, Collection<FieldScopeEntry> entries) {
    java.util.Map<String, FieldScopeEntry> validated = new java.util.LinkedHashMap<>();
    for (var entry : entries) {
      FieldScopeEntry clean = new FieldScopeEntry();
      clean.setResource(entry.getResource());
      clean.setField(entry.getField());
      clean.setAccess(entry.getAccess());
      clean.setFieldScopeId(fieldScope.getId());
      clean.setTenantId(fieldScope.getTenantId());
      if (validated.putIfAbsent(clean.getResource() + "#" + clean.getField(), clean) != null) {
        throw new org.simplepoint.core.permission.InvalidPermissionPolicyException("字段规则重复: " + clean.getResource() + "#" + clean.getField());
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

  /** A dedicated collection endpoint must not bypass the same explicit field policy as generic updates. */
  private void assertEntriesWritable(Collection<FieldScopeEntry> existing, Collection<FieldScopeEntry> proposed) {
    var old = entryValues(existing);
    var next = entryValues(proposed);
    if (!org.simplepoint.core.jackson.FieldPermissionPolicy.writable(FieldScope.class, "entries") && !old.equals(next)) {
      throw new org.springframework.security.access.AccessDeniedException("当前字段权限不允许修改字段规则集合");
    }
    for (var entry : next.entrySet()) {
      if (!old.containsKey(entry.getKey())) {
        for (var field : List.of("resource", "field", "access")) {
          org.simplepoint.core.jackson.FieldPermissionPolicy.requireWritable(FieldScopeEntry.class, field);
        }
      } else if (old.get(entry.getKey()) != entry.getValue()) {
        org.simplepoint.core.jackson.FieldPermissionPolicy.requireWritable(FieldScopeEntry.class, "access");
      }
    }
  }

  private static java.util.Map<String, org.simplepoint.security.entity.FieldAccessType> entryValues(Collection<FieldScopeEntry> entries) {
    var result = new java.util.LinkedHashMap<String, org.simplepoint.security.entity.FieldAccessType>();
    if (entries == null) return result;
    for (var entry : entries) {
      if (entry == null || entry.getResource() == null || entry.getField() == null || entry.getAccess() == null
          || result.putIfAbsent(entry.getResource() + "#" + entry.getField(), entry.getAccess()) != null) {
        throw new org.simplepoint.core.permission.InvalidPermissionPolicyException("字段规则无效或重复");
      }
    }
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends FieldScope> S create(S entity) {
    requireNoSubmittedEntries(entity);
    clearNonEditableFieldsForCreate(entity);
    validateMetadata(entity);
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
      requireNoSubmittedEntries(entity);
      clearNonEditableFieldsForCreate(entity);
      validateMetadata(entity);
      entity.setTenantId(tenantId);
    });
    List<FieldScope> result = super.create(entities);
    refreshCurrentTenantAuthorizationVersion();
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends FieldScope> FieldScope modifyById(S entity) {
    requireNoSubmittedEntries(entity);
    if (entity.getId() == null || entity.getId().isBlank()) throw new org.simplepoint.core.permission.InvalidPermissionPolicyException("规则标识不能为空");
    String tenantId = requireCurrentTenantId();
    FieldScope existing = getRepository().findByIdAndTenantIdAndDeletedAtIsNull(entity.getId(), tenantId)
        .orElseThrow(() -> new org.simplepoint.core.permission.InvalidPermissionPolicyException("规则不存在或不属于当前工作空间"));
    // Validate the same ACL-correct values that the generic writer will persist, never masked or omitted input.
    restoreNonWritableFields(existing, entity);
    validateMetadata(entity);
    entity.setTenantId(tenantId);
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

  private static void requireNoSubmittedEntries(FieldScope entity) {
    if (entity == null) throw new org.simplepoint.core.permission.InvalidPermissionPolicyException("规则不能为空");
    if (entity.getEntries() != null && !entity.getEntries().isEmpty()) {
      throw new org.simplepoint.core.permission.InvalidPermissionPolicyException("请使用字段规则配置接口修改 entries");
    }
  }

  private static void validateMetadata(FieldScope entity) {
    entity.setName(PermissionPolicyGuard.name(entity.getName()));
    if (entity.getDescription() != null && entity.getDescription().length() > 200) {
      throw new org.simplepoint.core.permission.InvalidPermissionPolicyException("规则描述不能超过 200 个字符");
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
