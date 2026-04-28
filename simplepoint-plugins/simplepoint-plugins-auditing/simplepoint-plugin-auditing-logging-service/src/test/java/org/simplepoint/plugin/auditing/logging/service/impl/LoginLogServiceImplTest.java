/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.logging.service.impl;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.plugin.auditing.logging.api.entity.LoginLog;
import org.simplepoint.plugin.auditing.logging.api.repository.LoginLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class LoginLogServiceImplTest {

  @Mock
  private LoginLogRepository repository;

  @Mock
  private DetailsProviderService detailsProviderService;

  @InjectMocks
  private LoginLogServiceImpl service;

  /**
   * When there is no tenant context (unauthenticated / no RequestContext),
   * currentTenantId() returns null so no "tenantId" key is injected.
   */
  @Test
  void limitShouldNotInjectTenantIdWhenContextIsAbsent() {
    Pageable pageable = PageRequest.of(0, 10);
    Page<LoginLog> emptyPage = new PageImpl<>(java.util.List.of());

    when(repository.limit(argThat(attrs -> !attrs.containsKey("tenantId")), eq(pageable)))
        .thenReturn(emptyPage);

    Page<LoginLog> result = service.limit(null, pageable);

    assertNotNull(result);
    verify(repository).limit(argThat(attrs -> !attrs.containsKey("tenantId")), eq(pageable));
  }

  @Test
  void limitShouldMergeCallerAttributesAndForwardToRepository() {
    Pageable pageable = PageRequest.of(0, 5);
    Map<String, String> attrs = Map.of("username", "alice");
    Page<LoginLog> emptyPage = new PageImpl<>(java.util.List.of());

    when(repository.limit(argThat(m -> "alice".equals(m.get("username"))), eq(pageable)))
        .thenReturn(emptyPage);

    Page<LoginLog> result = service.limit(attrs, pageable);

    assertNotNull(result);
    verify(repository).limit(argThat(m -> "alice".equals(m.get("username"))), eq(pageable));
  }

  @Test
  void limitShouldHandleNullAttributesGracefully() {
    Pageable pageable = PageRequest.of(0, 10);
    Page<LoginLog> emptyPage = new PageImpl<>(java.util.List.of());

    when(repository.limit(argThat(m -> m != null && !m.containsKey("tenantId")), eq(pageable)))
        .thenReturn(emptyPage);

    Page<LoginLog> result = service.limit(null, pageable);

    assertNotNull(result);
  }
}
