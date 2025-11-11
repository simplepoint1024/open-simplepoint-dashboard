/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.base.repository;

import java.util.Collection;
import java.util.Set;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RolePermissionsRelevanceVo;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.UserRoleRelevanceVo;
import org.simplepoint.plugin.rbac.core.api.repository.PermissionsRepository;
import org.simplepoint.security.entity.Permissions;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Repository interface for managing Permissions entities.
 * This interface extends BaseRepository and provides additional custom query methods
 * for retrieving permissions associated with specific roles.
 */
@Repository
public interface JpaPermissionsRepository extends BaseRepository<Permissions, String>,
    PermissionsRepository {

  @Override
  @Modifying
  @Query("delete from RolePermissionsRelevance p where p.roleAuthority = ?1 and p.permissionAuthority in ?2")
  void unauthorized(String roleAuthority, Set<String> authorities);

  @Override
  @Query("select new org.simplepoint.plugin.rbac.core.api.pojo.vo.RolePermissionsRelevanceVo(p.name,p.authority,p.description) from Permissions p ")
  Page<RolePermissionsRelevanceVo> permissionItems(Pageable pageable);

  @Override
  @Query("select permissionAuthority from RolePermissionsRelevance where roleAuthority = ?1")
  Collection<String> authorized(String roleAuthority);
}
