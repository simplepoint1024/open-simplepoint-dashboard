/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.api.service;

import java.util.Collection;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.RolePermissionsRelevance;

/**
 * Service interface for managing Role entities in the RBAC (Role-Based Access Control) system.
 * This interface extends BaseService to inherit common service operations and can be used
 * for defining role-specific business logic.
 */
public interface RoleService extends BaseService<Role, String> {
  /**
   * Load role-permission relationships based on a collection of role authorities.
   *
   * @param roleAuthorities A collection of role authorities to filter the relationships.
   * @return A collection of RolePermissionsRelevance entities associated with the given role authorities.
   */
  Collection<RolePermissionsRelevance> loadPermissionsByRoleAuthorities(
      Collection<String> roleAuthorities
  );
}
