package org.simplepoint.plugin.rbac.core.base.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.simplepoint.plugin.rbac.core.api.repository.RolePermissionsRelevanceRepository;
import org.simplepoint.security.entity.RolePermissionsRelevance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository interface for managing RolePermissionsRelevance entities.
 *
 * <p>This interface extends JpaRepository to provide basic CRUD functionality for role-permission relationships.
 */
@Repository
public interface JpaRolePermissionsRelevanceRepository extends JpaRepository<RolePermissionsRelevance, String>,
    RolePermissionsRelevanceRepository {

  @Override
  void deleteAllByroleId(String roleId);

  @Override
  @Modifying
  @Query("delete from RolePermissionsRelevance rpr where rpr.permissionAuthority in ?1")
  void deleteAllByPermissionAuthorities(Collection<String> authorities);

  @Override
  @Modifying
  @Query("delete from RolePermissionsRelevance rpr where rpr.roleId = ?1 and rpr.permissionAuthority in ?2")
  void unauthorized(String roleId, Set<String> authorities);

  @Override
  List<RolePermissionsRelevance> findByRoleIdIn(Collection<String> roleIds);

  @Override
  @Query("""
      select distinct rpr.tenantId
      from RolePermissionsRelevance rpr
      where rpr.permissionAuthority in ?1
        and rpr.tenantId is not null
        and rpr.tenantId <> ''
      """)
  Set<String> findTenantIdsByPermissionAuthorities(Collection<String> authorities);

  @Override
  Optional<RolePermissionsRelevance> findFirstByTenantIdAndRoleId(String tenantId, String roleId);

  @Override
  @Modifying
  @Query("update RolePermissionsRelevance r set r.dataScopeId = :dataScopeId, r.fieldScopeId = :fieldScopeId "
      + "where r.tenantId = :tenantId and r.roleId = :roleId")
  void updateScopeForRole(@Param("tenantId") String tenantId, @Param("roleId") String roleId,
                          @Param("dataScopeId") String dataScopeId, @Param("fieldScopeId") String fieldScopeId);

  @Override
  @Modifying
  @Query("""
      update RolePermissionsRelevance rpr
      set rpr.permissionAuthority = ?2
      where rpr.permissionAuthority = ?1
      """)
  void updatePermissionAuthority(String oldAuthority, String newAuthority);
}
