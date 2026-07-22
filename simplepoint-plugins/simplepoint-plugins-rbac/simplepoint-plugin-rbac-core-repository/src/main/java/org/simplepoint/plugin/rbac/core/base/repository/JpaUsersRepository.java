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
import java.util.Optional;
import org.simplepoint.core.authority.RoleGrantedAuthority;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.UserPickerItem;
import org.simplepoint.plugin.rbac.core.api.repository.UserRepository;
import org.simplepoint.security.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

  @Override
  @Query(value = "select * from simpoint_ac_users where id = :userId", nativeQuery = true)
  Optional<User> findByIdForAuthorization(@Param("userId") String userId);

  @Override
  @Query("select u from User u where u.id in :userIds and u.deletedAt is null")
  Collection<User> findAllByIdsForAuthorization(@Param("userIds") Collection<String> userIds);

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
        new org.simplepoint.core.authority.RoleGrantedAuthority(rl.id,rl.roleName,rl.authority)
      from UserRoleRelevance urr
      join Role rl on urr.roleId=rl.id
      where urr.userId = :userId and urr.tenantId = :tenantId and rl.tenantId = :tenantId
      """)
  Collection<RoleGrantedAuthority> loadRolesByUserId(@Param("tenantId") String tenantId, @Param("userId") String userId);

  @Override
  @Query("""
      select
        grant.resourceCode
      from RoleResourceGrant grant
      where grant.roleId in :roleIds
      """)
  Collection<String> loadResourcesInRoleIds(@Param("roleIds") List<String> roleIds);

  @Override
  @Query("select roleId from UserRoleRelevance where tenantId = :tenantId and userId = :userId")
  Collection<String> authorized(@Param("tenantId") String tenantId, @Param("userId") String userId);

  @Override
  @Query("""
      select new org.simplepoint.plugin.rbac.core.api.pojo.vo.UserPickerItem(
        u.id,
        coalesce(u.nickname, u.name, u.email, u.phoneNumber, u.id),
        u.email,
        u.phoneNumber,
        u.picture
      )
      from User u
      where u.enabled = true
        and (
          lower(coalesce(u.email, '')) like concat(lower(:keyword), '%')
          or coalesce(u.phoneNumber, '') like concat(:keyword, '%')
        )
      order by coalesce(u.nickname, u.name, u.email, u.phoneNumber, u.id), u.id
      """)
  Page<UserPickerItem> searchPickerItems(@Param("keyword") String keyword, Pageable pageable);

  @Override
  @Query("""
      select new org.simplepoint.plugin.rbac.core.api.pojo.vo.UserPickerItem(
        u.id,
        coalesce(u.nickname, u.name, u.email, u.phoneNumber, u.id),
        u.email,
        u.phoneNumber,
        u.picture
      )
      from User u
      where u.id in :userIds
      """)
  Collection<UserPickerItem> findPickerItemsByIds(@Param("userIds") Collection<String> userIds);
}
