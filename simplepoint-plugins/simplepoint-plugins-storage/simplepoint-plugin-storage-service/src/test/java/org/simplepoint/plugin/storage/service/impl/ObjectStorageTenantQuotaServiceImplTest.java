/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageTenantQuota;
import org.simplepoint.plugin.storage.api.repository.ObjectStorageObjectRepository;
import org.simplepoint.plugin.storage.api.repository.ObjectStorageTenantQuotaRepository;

@ExtendWith(MockitoExtension.class)
class ObjectStorageTenantQuotaServiceImplTest {

  @Mock
  private ObjectStorageTenantQuotaRepository repository;

  @Mock
  private DetailsProviderService detailsProviderService;

  @Mock
  private ObjectStorageObjectRepository objectRepository;

  @Mock
  private TenantRepository tenantRepository;

  @InjectMocks
  private ObjectStorageTenantQuotaServiceImpl service;

  // ── create ────────────────────────────────────────────────────────────────

  @Test
  void createShouldThrowWhenEntityIsNull() {
    ObjectStorageTenantQuota nullQuota = null;
    assertThrows(IllegalArgumentException.class, () -> service.create(nullQuota));
  }

  @Test
  void createShouldThrowWhenTenantIdIsBlank() {
    ObjectStorageTenantQuota quota = new ObjectStorageTenantQuota();
    quota.setTenantId("  ");
    assertThrows(IllegalArgumentException.class, () -> service.create(quota));
  }

  @Test
  void createShouldThrowWhenTenantDoesNotExist() {
    ObjectStorageTenantQuota quota = new ObjectStorageTenantQuota();
    quota.setTenantId("t1");
    when(tenantRepository.findById("t1")).thenReturn(Optional.empty());
    assertThrows(IllegalArgumentException.class, () -> service.create(quota));
  }

  @Test
  void createShouldThrowWhenDuplicateQuotaForTenant() {
    ObjectStorageTenantQuota quota = new ObjectStorageTenantQuota();
    quota.setTenantId("t1");
    when(tenantRepository.findById("t1")).thenReturn(Optional.of(fakeTenant("t1")));
    when(repository.findActiveByTenantId("t1")).thenReturn(Optional.of(existingQuota("existing-id")));
    assertThrows(IllegalArgumentException.class, () -> service.create(quota));
  }

  @Test
  void createShouldThrowWhenQuotaBytesIsNegative() {
    ObjectStorageTenantQuota quota = new ObjectStorageTenantQuota();
    quota.setTenantId("t1");
    quota.setQuotaBytes(-1L);
    when(tenantRepository.findById("t1")).thenReturn(Optional.of(fakeTenant("t1")));
    when(repository.findActiveByTenantId("t1")).thenReturn(Optional.empty());
    assertThrows(IllegalArgumentException.class, () -> service.create(quota));
  }

  @Test
  void createShouldSucceedAndDecorateWithUsage() {
    ObjectStorageTenantQuota quota = new ObjectStorageTenantQuota();
    quota.setTenantId("t1");
    quota.setQuotaBytes(1000L);

    when(tenantRepository.findById("t1")).thenReturn(Optional.of(fakeTenant("t1")));
    when(repository.findActiveByTenantId("t1")).thenReturn(Optional.empty());
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(objectRepository.sumActiveContentLengthByTenantIds(any())).thenReturn(Collections.emptyList());
    when(tenantRepository.findAllByIds(any())).thenReturn(Collections.emptyList());

    ObjectStorageTenantQuota saved = service.create(quota);

    assertNotNull(saved);
    verify(repository).save(any());
    // enabled defaults to true when not set
    assertTrue(Boolean.TRUE.equals(saved.getEnabled()));
  }

  @Test
  void createShouldDefaultEnabledToTrue() {
    ObjectStorageTenantQuota quota = new ObjectStorageTenantQuota();
    quota.setTenantId("t1");

    when(tenantRepository.findById("t1")).thenReturn(Optional.of(fakeTenant("t1")));
    when(repository.findActiveByTenantId("t1")).thenReturn(Optional.empty());
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(objectRepository.sumActiveContentLengthByTenantIds(any())).thenReturn(Collections.emptyList());
    when(tenantRepository.findAllByIds(any())).thenReturn(Collections.emptyList());

    ObjectStorageTenantQuota saved = service.create(quota);
    assertTrue(Boolean.TRUE.equals(saved.getEnabled()));
  }

  // ── modifyById ────────────────────────────────────────────────────────────

  @Test
  void modifyByIdShouldThrowWhenQuotaNotFound() {
    ObjectStorageTenantQuota update = new ObjectStorageTenantQuota();
    update.setId("missing");
    update.setTenantId("t1");
    when(repository.findActiveById("missing")).thenReturn(Optional.empty());
    assertThrows(IllegalArgumentException.class, () -> service.modifyById(update));
  }

  @Test
  void modifyByIdShouldPreserveExplicitlySetEnabledFalse() {
    ObjectStorageTenantQuota current = existingQuota("q1");
    current.setTenantId("t1");
    current.setEnabled(true);

    ObjectStorageTenantQuota update = new ObjectStorageTenantQuota();
    update.setId("q1");
    update.setTenantId("t1");
    update.setEnabled(false);   // explicitly set to false

    when(repository.findActiveById("q1")).thenReturn(Optional.of(current));
    when(tenantRepository.findById("t1")).thenReturn(Optional.of(fakeTenant("t1")));
    when(repository.findActiveByTenantId("t1")).thenReturn(Optional.of(current));
    when(repository.updateById(any())).thenAnswer(inv -> inv.getArgument(0));
    when(objectRepository.sumActiveContentLengthByTenantIds(any())).thenReturn(Collections.emptyList());
    when(tenantRepository.findAllByIds(any())).thenReturn(Collections.emptyList());

    ObjectStorageTenantQuota result = service.modifyById(update);
    assertEquals(Boolean.FALSE, result.getEnabled());
  }

  // ── findActiveByTenantId ──────────────────────────────────────────────────

  @Test
  void findActiveByTenantIdShouldReturnEmptyWhenNotFound() {
    when(repository.findActiveByTenantId("unknown")).thenReturn(Optional.empty());
    assertTrue(service.findActiveByTenantId("unknown").isEmpty());
  }

  @Test
  void findActiveByTenantIdShouldDecorateQuotaWithUsage() {
    ObjectStorageTenantQuota quota = existingQuota("q1");
    quota.setTenantId("t1");
    quota.setQuotaBytes(500L);

    when(repository.findActiveByTenantId("t1")).thenReturn(Optional.of(quota));
    when(objectRepository.sumActiveContentLengthByTenantIds(any())).thenReturn(
        Collections.singletonList(new ObjectStorageObjectRepository.TenantStorageUsage() {
          @Override public String getTenantId() { return "t1"; }
          @Override public Long getUsedBytes() { return 200L; }
        })
    );
    when(tenantRepository.findAllByIds(any())).thenReturn(Collections.singletonList(fakeTenant("t1")));

    Optional<ObjectStorageTenantQuota> result = service.findActiveByTenantId("t1");
    assertTrue(result.isPresent());
    assertEquals(200L, result.get().getUsedBytes());
    assertEquals(300L, result.get().getRemainingBytes());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static Tenant fakeTenant(final String id) {
    Tenant tenant = new Tenant();
    tenant.setId(id);
    tenant.setName("Tenant-" + id);
    return tenant;
  }

  private static ObjectStorageTenantQuota existingQuota(final String id) {
    ObjectStorageTenantQuota quota = new ObjectStorageTenantQuota();
    quota.setId(id);
    return quota;
  }
}
