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
import org.simplepoint.plugin.rbac.tenant.api.service.PermissionVersionRefreshService;
import org.simplepoint.security.entity.DataScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service implementation for managing {@link DataScope} entities.
 * Invalidates the tenant permission cache on every mutation so that
 * AuthorizationContext rebuild picks up the updated scope immediately.
 */
@Service
public class DataScopeServiceImpl extends BaseServiceImpl<DataScopeRepository, DataScope, String>
    implements DataScopeService {

  private final PermissionVersionRefreshService permissionVersionRefreshService;

  /**
   * Data Scope Service Impl.
   */
  public DataScopeServiceImpl(
      DataScopeRepository repository,
      DetailsProviderService detailsProviderService,
      PermissionVersionRefreshService permissionVersionRefreshService
  ) {
    super(repository, detailsProviderService);
    this.permissionVersionRefreshService = permissionVersionRefreshService;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends DataScope> S create(S entity) {
    S result = super.create(entity);
    refreshCurrentTenantPermissionVersion();
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<DataScope> create(Collection<DataScope> entities) {
    List<DataScope> result = super.create(entities);
    refreshCurrentTenantPermissionVersion();
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends DataScope> DataScope modifyById(S entity) {
    DataScope result = super.modifyById(entity);
    refreshCurrentTenantPermissionVersion();
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeByIds(Collection<String> ids) {
    super.removeByIds(ids);
    refreshCurrentTenantPermissionVersion();
  }

  private void refreshCurrentTenantPermissionVersion() {
    String tenantId = currentTenantId();
    permissionVersionRefreshService.refreshTenant(tenantId);
  }
}
