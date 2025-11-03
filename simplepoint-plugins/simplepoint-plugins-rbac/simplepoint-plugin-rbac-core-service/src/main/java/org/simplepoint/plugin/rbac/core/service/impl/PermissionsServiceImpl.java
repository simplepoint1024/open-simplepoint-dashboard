/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.service.impl;

import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.core.context.UserContext;
import org.simplepoint.plugin.rbac.core.api.repository.PermissionsRepository;
import org.simplepoint.plugin.rbac.core.api.service.PermissionsService;
import org.simplepoint.security.entity.Permissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * PermissionsServiceImpl provides the implementation for PermissionsService.
 * It utilizes the repository to load permissions associated with a specific role authority.
 */
@Service
public class PermissionsServiceImpl
    extends BaseServiceImpl<PermissionsRepository, Permissions, String>
    implements PermissionsService {

  /**
   * Constructs a PermissionsServiceImpl with the specified repository, user context, and details provider service.
   *
   * @param repository             the PermissionsRepository for data access
   * @param userContext            the UserContext for accessing user-related information
   * @param detailsProviderService the DetailsProviderService for additional details
   */
  public PermissionsServiceImpl(
      PermissionsRepository repository,
      @Autowired(required = false) UserContext<BaseUser> userContext,
      DetailsProviderService detailsProviderService
  ) {
    super(repository, userContext, detailsProviderService);
  }
}
