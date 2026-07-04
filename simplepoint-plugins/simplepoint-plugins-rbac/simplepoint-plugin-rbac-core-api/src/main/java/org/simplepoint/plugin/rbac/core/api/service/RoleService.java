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
import org.simplepoint.plugin.rbac.core.api.pojo.dto.RoleResourceGrantDto;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RoleRelevanceVo;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RoleScopeAssignmentVo;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.RoleResourceGrant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Service interface for managing Role entities in the RBAC (Role-Based Access Control) system.
 * This interface extends BaseService to inherit common service operations and can be used
 * for defining role-specific business logic.
 */
public interface RoleService extends BaseService<Role, String> {

  /**
   * Retrieve a paginated list of RoleSelectDto for role selection purposes.
   *
   * @param pageable Pagination information.
   * @return A page of RoleSelectDto containing role selection data.
   */
  Page<RoleRelevanceVo> roleSelectItems(Pageable pageable);


  /**
   * Removes resource grants from a role.
   *
   * @param roleId the role id
   * @param resourceCodes resource codes to be removed
   */
  void unauthorized(String roleId, Set<String> resourceCodes);

  /**
   * Retrieves resource codes granted to the specified role.
   *
   * @param roleId the authority of the role
   * @return resource codes granted to the role
   */
  Collection<String> authorized(String roleId);

  /**
   * Grants resources based on the provided DTO.
   *
   * @param dto role resource grant DTO
   * @return saved grants
   */
  Collection<RoleResourceGrant> authorize(RoleResourceGrantDto dto);

  /**
   * Returns the current data scope and field scope assignment for a role.
   *
   * @param roleId the role ID
   * @return a VO with the role's current dataScopeId and fieldScopeId
   */
  RoleScopeAssignmentVo getScopeAssignment(String roleId);

  /**
   * Updates the data scope and field scope for all resource grants belonging to a role.
   *
   * @param vo VO containing roleId, dataScopeId, and fieldScopeId
   */
  void updateScopeAssignment(RoleScopeAssignmentVo vo);

}
