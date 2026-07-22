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

  /**
   * Field Scope Service Impl.
   */
  public FieldScopeServiceImpl(
      FieldScopeRepository repository,
      DetailsProviderService detailsProviderService,
      ResourceAuthorizationVersionService resourceAuthorizationVersionService
  ) {
    super(repository, detailsProviderService);
    this.resourceAuthorizationVersionService = resourceAuthorizationVersionService;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public FieldScope replaceEntries(String fieldScopeId, Collection<FieldScopeEntry> entries) {
    FieldScope fieldScope = getRepository().findById(fieldScopeId)
        .orElseThrow(() -> new IllegalArgumentException("FieldScope not found: " + fieldScopeId));
    fieldScope.getEntries().clear();
    if (entries != null) {
      String tenantId = requireCurrentTenantId();
      entries.forEach(entry -> {
        entry.setFieldScopeId(fieldScopeId);
        entry.setTenantId(tenantId);
        fieldScope.getEntries().add(entry);
      });
    }
    FieldScope result = getRepository().save(fieldScope);
    refreshCurrentTenantAuthorizationVersion();
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends FieldScope> S create(S entity) {
    entity.setTenantId(requireCurrentTenantId());
    S result = super.create(entity);
    refreshCurrentTenantAuthorizationVersion();
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<FieldScope> create(Collection<FieldScope> entities) {
    String tenantId = requireCurrentTenantId();
    entities.forEach(entity -> entity.setTenantId(tenantId));
    List<FieldScope> result = super.create(entities);
    refreshCurrentTenantAuthorizationVersion();
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends FieldScope> FieldScope modifyById(S entity) {
    entity.setTenantId(requireCurrentTenantId());
    FieldScope result = super.modifyById(entity);
    refreshCurrentTenantAuthorizationVersion();
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeByIds(Collection<String> ids) {
    super.removeByIds(ids);
    refreshCurrentTenantAuthorizationVersion();
  }

  private void refreshCurrentTenantAuthorizationVersion() {
    String tenantId = currentTenantId();
    resourceAuthorizationVersionService.refreshTenant(tenantId);
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
