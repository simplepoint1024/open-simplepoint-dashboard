/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.api.service;

import java.util.Collection;
import java.util.List;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.UserRoleRelevanceDto;
import org.simplepoint.security.entity.User;
import org.simplepoint.security.entity.UserRoleRelevance;
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
  List<String> loadPermissionsInRoleAuthorities(List<String> roleAuthorities);

  /**
   * Retrieve a collection of role authorities associated with a specific username.
   *
   * @param username The username to filter the role authorities.
   * @return A collection of role authorities for the given username.
   */
  Collection<String> authorized(String username);


  /**
   * Authorize roles based on the provided RoleSelectDto.
   *
   * @param dto The RoleSelectDto containing authorization criteria.
   * @return A collection of UserRoleRelevance entities that match the authorization criteria.
   */
  Collection<UserRoleRelevance> authorize(UserRoleRelevanceDto dto);

  /**
   * unauthorized roles based on the provided RoleSelectDto.
   *
   * @param dto The RoleSelectDto containing unauthorization criteria.
   */
  void unauthorized(UserRoleRelevanceDto dto);
}
