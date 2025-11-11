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
import org.simplepoint.plugin.rbac.core.api.pojo.dto.UserRoleRelevanceDto;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.UserRoleRelevanceVo;
import org.simplepoint.plugin.rbac.core.api.repository.UserRoleRelevanceRepository;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.RolePermissionsRelevance;
import org.simplepoint.security.entity.UserRoleRelevance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Service interface for managing Role entities in the RBAC (Role-Based Access Control) system.
 * This interface extends BaseService to inherit common service operations and can be used
 * for defining role-specific business logic.
 */
public interface RoleService extends BaseService<Role, String> {

  /**
   * Get the UserRoleRelevanceRepository instance.
   *
   * @return The UserRoleRelevanceRepository.
   */
  UserRoleRelevanceRepository getUserRoleRelevanceRepository();

  /**
   * Load role-permission relationships based on a collection of role authorities.
   *
   * @param roleAuthorities A collection of role authorities to filter the relationships.
   * @return A collection of RolePermissionsRelevance entities associated with the given role authorities.
   */
  Collection<RolePermissionsRelevance> loadPermissionsByRoleAuthorities(
      Collection<String> roleAuthorities
  );

  /**
   * Retrieve a paginated list of RoleSelectDto for role selection purposes.
   *
   * @param pageable Pagination information.
   * @return A page of RoleSelectDto containing role selection data.
   */
  Page<UserRoleRelevanceVo> roleSelectItems(Pageable pageable);

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
