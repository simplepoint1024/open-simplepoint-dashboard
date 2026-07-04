/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.logging.service.impl;

import java.util.LinkedHashMap;
import java.util.Map;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.auditing.logging.api.entity.ResourceGrantLog;
import org.simplepoint.plugin.auditing.logging.api.repository.ResourceGrantLogRepository;
import org.simplepoint.plugin.auditing.logging.api.service.ResourceGrantLogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Resource grant log service implementation.
 */
@Service
public class ResourceGrantLogServiceImpl extends BaseServiceImpl<ResourceGrantLogRepository, ResourceGrantLog, String>
    implements ResourceGrantLogService {

  /**
   * Creates the service with the repository and details provider required by the base service.
   *
   * @param repository             the resource grant log repository
   * @param detailsProviderService the details provider service
   */
  public ResourceGrantLogServiceImpl(
      final ResourceGrantLogRepository repository,
      final DetailsProviderService detailsProviderService
  ) {
    super(repository, detailsProviderService);
  }

  @Override
  public <S extends ResourceGrantLog> Page<S> limit(Map<String, String> attributes, Pageable pageable) {
    Map<String, String> normalizedAttributes = new LinkedHashMap<>();
    if (attributes != null) {
      normalizedAttributes.putAll(attributes);
    }
    String tenantId = currentTenantId();
    if (tenantId != null && !tenantId.isBlank()) {
      normalizedAttributes.putIfAbsent("tenantId", tenantId);
    }
    return super.limit(normalizedAttributes, pageable);
  }
}
