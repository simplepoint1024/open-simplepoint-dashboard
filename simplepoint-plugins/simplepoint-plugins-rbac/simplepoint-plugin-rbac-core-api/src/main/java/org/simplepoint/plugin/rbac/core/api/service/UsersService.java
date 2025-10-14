/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.api.service;

import java.util.List;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.security.entity.RolePermissionsRelevance;
import org.simplepoint.security.entity.User;
import org.springframework.security.core.userdetails.UserDetailsService;

/**
 * Service interface for managing User entities in the RBAC (Role-Based Access Control) system.
 * This interface extends BaseService to inherit common service operations and provides
 * additional methods for handling user-specific functionality.
 */
public interface UsersService extends BaseService<User, String>, UserDetailsService {

  /**
   * Loads the roles associated with the given username.
   * This method retrieves a list of role authorities assigned to the specified user.
   *
   * @param username the username of the user whose roles are to be loaded
   * @return a list of role authorities associated with the user
   */
  List<String> loadRolesByUsername(String username);

  /**
   * Loads permissions associated with the given role authorities.
   * This method retrieves a list of RolePermissionsRelevance entities that represent
   * the permissions assigned to the specified roles.
   *
   * @param roleAuthorities a list of role authorities for which to load permissions
   * @return a list of RolePermissionsRelevance associated with the specified role authorities
   */
  List<RolePermissionsRelevance> loadPermissionsInRoleAuthorities(List<String> roleAuthorities);
}
