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
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RoleRelevanceVo;
import org.simplepoint.plugin.rbac.core.api.repository.RoleRepository;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.RolePermissionsRelevance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository interface for managing Role entities.
 *
 * <p>The relationship between roles and permissions in the RBAC (Role-Based Access Control) system
 * is many-to-many, meaning multiple roles can have multiple permissions associated with them.
 * This interface extends BaseRepository and provides basic CRUD functionality for roles.
 */
@Repository
public interface JpaRolesRepository extends BaseRepository<Role, String>, RoleRepository {
  @Override
  @Query("SELECT rpr FROM RolePermissionsRelevance rpr WHERE rpr.roleAuthority IN :roleAuthorities")
  Collection<RolePermissionsRelevance> loadPermissionsByRoleAuthorities(@Param("roleAuthorities") Collection<String> roleAuthorities);

  @Override
  @Query("SELECT new org.simplepoint.plugin.rbac.core.api.pojo.vo.RoleRelevanceVo(r.roleName, r.authority, r.description) FROM Role r")
  Page<RoleRelevanceVo> roleSelectItems(Pageable pageable);

  @Override
  @Modifying
  @Query("delete from RolePermissionsRelevance p where p.roleAuthority = ?1 and p.permissionAuthority in ?2")
  void unauthorized(String roleAuthority, Set<String> authorities);

  @Override
  @Query("select permissionAuthority from RolePermissionsRelevance where roleAuthority = ?1")
  Collection<String> authorized(String roleAuthority);

}
