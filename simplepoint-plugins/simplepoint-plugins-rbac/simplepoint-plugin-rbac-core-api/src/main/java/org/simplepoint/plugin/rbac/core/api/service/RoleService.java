/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.api.service;

import java.util.Collection;
import java.util.Set;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.RolePermissionsRelevanceDto;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.UserRoleRelevanceDto;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RoleRelevanceVo;
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
  Page<RoleRelevanceVo> roleSelectItems(Pageable pageable);


  /**
   * Removes the specified authorities from the given role authority.
   *
   * @param roleAuthority the authority of the role
   * @param authorities   the set of permission authorities to be removed
   */
  void unauthorized(String roleAuthority, Set<String> authorities);

  /**
   * Retrieves a collection of authorized permission strings for the specified role authority.
   *
   * @param roleAuthority the authority of the role
   * @return a collection of authorized permission strings
   */
  Collection<String> authorized(String roleAuthority);

  /**
   * Authorizes permissions based on the provided RolePermissionsRelevanceDto.
   *
   * @param dto the RolePermissionsRelevanceDto containing authorization details
   * @return a collection of RolePermissionsRelevance entities representing the authorized permissions
   */
  Collection<RolePermissionsRelevance> authorize(RolePermissionsRelevanceDto dto);

}
