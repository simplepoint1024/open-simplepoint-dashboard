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
import org.simplepoint.plugin.rbac.core.api.pojo.vo.PermissionsRelevanceVo;
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
   * Retrieves a paginated list of permission items.
   *
   * @param pageable the pagination information
   * @return a page of RolePermissionsRelevanceVo containing permission items
   */
  Page<PermissionsRelevanceVo> permissionItems(Pageable pageable);
}
