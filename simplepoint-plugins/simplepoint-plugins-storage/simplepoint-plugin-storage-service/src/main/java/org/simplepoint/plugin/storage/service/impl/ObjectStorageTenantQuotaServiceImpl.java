/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.service.impl;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageTenantQuota;
import org.simplepoint.plugin.storage.api.repository.ObjectStorageObjectRepository;
import org.simplepoint.plugin.storage.api.repository.ObjectStorageTenantQuotaRepository;
import org.simplepoint.plugin.storage.api.service.ObjectStorageTenantQuotaService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Tenant quota service implementation.
 */
@Service
public class ObjectStorageTenantQuotaServiceImpl
    extends BaseServiceImpl<ObjectStorageTenantQuotaRepository, ObjectStorageTenantQuota, String>
    implements ObjectStorageTenantQuotaService {

  private final ObjectStorageTenantQuotaRepository repository;

  private final ObjectStorageObjectRepository objectRepository;

  private final TenantRepository tenantRepository;

  public ObjectStorageTenantQuotaServiceImpl(
      final ObjectStorageTenantQuotaRepository repository,
      final DetailsProviderService detailsProviderService,
      final ObjectStorageObjectRepository objectRepository,
      final TenantRepository tenantRepository
  ) {
    super(repository, detailsProviderService);
    this.repository = repository;
    this.objectRepository = objectRepository;
    this.tenantRepository = tenantRepository;
  }

  @Override
  public Optional<ObjectStorageTenantQuota> findActiveByTenantId(final String tenantId) {
    return repository.findActiveByTenantId(tenantId)
        .map(this::decorateQuota);
  }

  @Override
  public <S extends ObjectStorageTenantQuota> Page<S> limit(final Map<String, String> attributes, final Pageable pageable) {
    Map<String, String> normalized = new LinkedHashMap<>();
    if (attributes != null) {
      normalized.putAll(attributes);
    }
    normalized.put("deletedAt", "is:null");
    String tenantId = normalized.get("tenantId");
    if (tenantId != null && !tenantId.isBlank() && !tenantId.contains(":")) {
      normalized.put("tenantId", "like:" + tenantId.trim());
    }
    Page<S> page = super.limit(normalized, pageable);
    decorateQuotas(page.getContent());
    return page;
  }

  @Override
  public <S extends ObjectStorageTenantQuota> S create(final S entity) {
    normalizeAndValidate(entity, null);
    S saved = super.create(entity);
    decorateQuotas(Set.of(saved));
    return saved;
  }

  @Override
  public <S extends ObjectStorageTenantQuota> ObjectStorageTenantQuota modifyById(final S entity) {
    ObjectStorageTenantQuota current = repository.findActiveById(entity.getId())
        .orElseThrow(() -> new IllegalArgumentException("配额不存在"));
    normalizeAndValidate(entity, current.getId());
    if (entity.getEnabled() == null) {
      entity.setEnabled(current.getEnabled());
    }
    ObjectStorageTenantQuota updated = (ObjectStorageTenantQuota) super.modifyById(entity);
    decorateQuotas(Set.of(updated));
    return updated;
  }

  private void normalizeAndValidate(final ObjectStorageTenantQuota entity, final String currentId) {
    if (entity == null) {
      throw new IllegalArgumentException("配额不能为空");
    }
    String tenantId = trimToNull(entity.getTenantId());
    if (tenantId == null) {
      throw new IllegalArgumentException("租户ID不能为空");
    }
    entity.setTenantId(tenantId);
    tenantRepository.findById(tenantId).orElseThrow(() -> new IllegalArgumentException("租户不存在: " + tenantId));
    repository.findActiveByTenantId(tenantId)
        .filter(existing -> currentId == null || !existing.getId().equals(currentId))
        .ifPresent(existing -> {
          throw new IllegalArgumentException("该租户已存在对象存储配额配置");
        });
    if (entity.getEnabled() == null) {
      entity.setEnabled(true);
    }
    if (entity.getQuotaBytes() != null && entity.getQuotaBytes() < 0) {
      throw new IllegalArgumentException("配额不能小于 0");
    }
  }

  private ObjectStorageTenantQuota decorateQuota(final ObjectStorageTenantQuota quota) {
    decorateQuotas(Set.of(quota));
    return quota;
  }

  private void decorateQuotas(final Collection<? extends ObjectStorageTenantQuota> quotas) {
    if (quotas == null || quotas.isEmpty()) {
      return;
    }
    Set<String> tenantIds = quotas.stream()
        .map(ObjectStorageTenantQuota::getTenantId)
        .filter(tenantId -> tenantId != null && !tenantId.isBlank())
        .collect(Collectors.toSet());
    Map<String, Long> usage = objectRepository.sumActiveContentLengthByTenantIds(tenantIds).stream()
        .collect(Collectors.toMap(
            ObjectStorageObjectRepository.TenantStorageUsage::getTenantId,
            projection -> Optional.ofNullable(projection.getUsedBytes()).orElse(0L)
        ));
    Map<String, String> tenantNames = tenantRepository.findAllByIds(tenantIds).stream()
        .collect(Collectors.toMap(Tenant::getId, Tenant::getName, (left, right) -> left));
    quotas.forEach(quota -> {
      long usedBytes = usage.getOrDefault(quota.getTenantId(), 0L);
      quota.setTenantName(tenantNames.get(quota.getTenantId()));
      quota.setUsedBytes(usedBytes);
      Long maxBytes = quota.getQuotaBytes();
      quota.setRemainingBytes(maxBytes == null ? null : Math.max(maxBytes - usedBytes, 0L));
    });
  }

  private static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
