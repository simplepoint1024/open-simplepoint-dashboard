/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.base.repository;

import java.util.Collection;
import java.util.List;
import org.simplepoint.core.authority.PermissionGrantedAuthority;
import org.simplepoint.core.authority.RoleGrantedAuthority;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.rbac.core.api.repository.UserRepository;
import org.simplepoint.security.entity.User;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository interface for managing User entities.
 *
 * <p>This interface extends BaseRepository and provides additional custom query methods
 * for retrieving roles associated with specific users based on their usernames.
 */
@Repository
public interface JpaUsersRepository extends BaseRepository<User, String>, UserRepository {

  /**
   * Loads the roles associated with the given username.
   * This custom query retrieves the role authorities for the specified user.
   *
   * @param userId the username of the user whose roles are to be loaded
   * @return a list of role authorities associated with the specified username
   */
  @Override
  @Query("""
      select
        new org.simplepoint.core.authority.RoleGrantedAuthority(rl.id,rl.authority)
      from UserRoleRelevance urr
      join Role rl on urr.roleId=rl.id
      where urr.userId = :userId
      """)
  Collection<RoleGrantedAuthority> loadRolesByUserId(@Param("userId") String userId);

  @Override
  @Query("""
      select
        new org.simplepoint.core.authority.PermissionGrantedAuthority(ps.id,ps.authority,pr.roleId,null)
      from RolePermissionsRelevance pr
      join Permissions ps on pr.permissionId = ps.id
      where pr.roleId in :roleIds
      """)
  Collection<PermissionGrantedAuthority> loadPermissionsInRoleIds(@Param("roleIds") List<String> roleIds);

  @Override
  @Query("select roleId from UserRoleRelevance where userId = :userId")
  Collection<String> authorized(@Param("userId") String userId);
}
