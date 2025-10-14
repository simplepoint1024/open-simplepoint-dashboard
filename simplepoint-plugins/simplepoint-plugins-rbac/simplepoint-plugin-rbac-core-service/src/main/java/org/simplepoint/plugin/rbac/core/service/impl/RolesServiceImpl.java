/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.service.impl;

import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.rbac.core.api.repository.RoleRepository;
import org.simplepoint.plugin.rbac.core.api.service.RolesService;
import org.simplepoint.security.entity.Role;
import org.springframework.stereotype.Service;

/**
 * Service implementation class for managing Role entities in the RBAC (Role-Based Access Control) system.
 * This class extends the BaseServiceImpl to inherit common CRUD operations and implements the RolesService
 * interface for defining role-specific business logic.
 */
@Service
public class RolesServiceImpl extends BaseServiceImpl<RoleRepository, Role, String>
    implements RolesService {

  /**
   * Constructs a new RolesServiceImpl with the specified repository and optional metadata sync service.
   *
   * @param rolesRepository the repository used for role operations
   */
  public RolesServiceImpl(final RoleRepository rolesRepository) {
    super(rolesRepository);
  }

}
