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
import java.util.Set;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.rbac.core.api.repository.FieldScopeRepository;
import org.simplepoint.plugin.rbac.core.api.service.FieldScopeService;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.security.entity.FieldScope;
import org.simplepoint.security.entity.FieldScopeEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service implementation for managing {@link FieldScope} entities.
 * Invalidates the tenant permission cache on every mutation so that
 * AuthorizationContext rebuild picks up the updated scope immediately.
 */
@Service
public class FieldScopeServiceImpl extends BaseServiceImpl<FieldScopeRepository, FieldScope, String>
    implements FieldScopeService {

  private final TenantRepository tenantRepository;

  public FieldScopeServiceImpl(
      FieldScopeRepository repository,
      DetailsProviderService detailsProviderService,
      @Autowired(required = false) TenantRepository tenantRepository
  ) {
    super(repository, detailsProviderService);
    this.tenantRepository = tenantRepository;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public FieldScope replaceEntries(String fieldScopeId, Collection<FieldScopeEntry> entries) {
    FieldScope fieldScope = getRepository().findById(fieldScopeId)
        .orElseThrow(() -> new IllegalArgumentException("FieldScope not found: " + fieldScopeId));
    fieldScope.getEntries().clear();
    if (entries != null) {
      entries.forEach(entry -> {
        entry.setFieldScopeId(fieldScopeId);
        fieldScope.getEntries().add(entry);
      });
    }
    FieldScope result = getRepository().save(fieldScope);
    refreshCurrentTenantPermissionVersion();
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends FieldScope> S create(S entity) {
    S result = super.create(entity);
    refreshCurrentTenantPermissionVersion();
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<FieldScope> create(Collection<FieldScope> entities) {
    List<FieldScope> result = super.create(entities);
    refreshCurrentTenantPermissionVersion();
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends FieldScope> FieldScope modifyById(S entity) {
    FieldScope result = super.modifyById(entity);
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
    if (tenantRepository == null) {
      return;
    }
    String tenantId = currentTenantId();
    if (tenantId == null || tenantId.isBlank() || "default".equals(tenantId)) {
      return;
    }
    tenantRepository.increasePermissionVersion(Set.of(tenantId));
  }
}
