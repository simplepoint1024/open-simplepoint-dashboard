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
import org.simplepoint.data.amqp.annotation.AmqpRemoteClient;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.RolePermissionsRelevanceDto;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RolePermissionsRelevanceVo;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.UserRoleRelevanceVo;
import org.simplepoint.security.entity.Permissions;
import org.simplepoint.security.entity.RolePermissionsRelevance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * PermissionsService interface provides methods for handling permissions-related operations.
 * It includes a custom query to retrieve permission authorities by role authority.
 */
@AmqpRemoteClient(to = "security.permission")
public interface PermissionsService extends BaseService<Permissions, String> {
  /**
   * Removes the specified authorities from the given role authority.
   *
   * @param roleAuthority the authority of the role
   * @param authorities   the set of permission authorities to be removed
   */
  void unauthorized(String roleAuthority, Set<String> authorities);

  /**
   * Retrieves a paginated list of permission items.
   *
   * @param pageable the pagination information
   * @return a page of RolePermissionsRelevanceVo containing permission items
   */
  Page<RolePermissionsRelevanceVo> permissionItems(Pageable pageable);

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
