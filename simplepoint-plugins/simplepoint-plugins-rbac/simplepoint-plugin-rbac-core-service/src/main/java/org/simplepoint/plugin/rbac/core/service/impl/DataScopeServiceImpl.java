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
import org.simplepoint.plugin.rbac.core.api.repository.DataScopeRepository;
import org.simplepoint.plugin.rbac.core.api.service.DataScopeService;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.security.entity.DataScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service implementation for managing {@link DataScope} entities.
 * Invalidates the tenant authorization cache on every mutation so that
 * AuthorizationContext rebuild picks up the updated scope immediately.
 */
@Service
public class DataScopeServiceImpl extends BaseServiceImpl<DataScopeRepository, DataScope, String>
    implements DataScopeService {

  private final ResourceAuthorizationVersionService resourceAuthorizationVersionService;

  /**
   * Data Scope Service Impl.
   */
  public DataScopeServiceImpl(
      DataScopeRepository repository,
      DetailsProviderService detailsProviderService,
      ResourceAuthorizationVersionService resourceAuthorizationVersionService
  ) {
    super(repository, detailsProviderService);
    this.resourceAuthorizationVersionService = resourceAuthorizationVersionService;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends DataScope> S create(S entity) {
    entity.setTenantId(requireCurrentTenantId());
    S result = super.create(entity);
    refreshCurrentTenantAuthorizationVersion();
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<DataScope> create(Collection<DataScope> entities) {
    String tenantId = requireCurrentTenantId();
    entities.forEach(entity -> entity.setTenantId(tenantId));
    List<DataScope> result = super.create(entities);
    refreshCurrentTenantAuthorizationVersion();
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends DataScope> DataScope modifyById(S entity) {
    entity.setTenantId(requireCurrentTenantId());
    DataScope result = super.modifyById(entity);
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
