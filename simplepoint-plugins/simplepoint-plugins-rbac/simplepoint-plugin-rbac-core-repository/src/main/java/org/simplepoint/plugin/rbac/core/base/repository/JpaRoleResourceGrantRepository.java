package org.simplepoint.plugin.rbac.core.base.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.simplepoint.plugin.rbac.core.api.repository.RoleResourceGrantRepository;
import org.simplepoint.security.entity.RoleResourceGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for role resource grants.
 */
@Repository
public interface JpaRoleResourceGrantRepository extends JpaRepository<RoleResourceGrant, String>,
    RoleResourceGrantRepository {

  @Override
  void deleteAllByroleId(String roleId);

  @Override
  @Modifying
  @Query("delete from RoleResourceGrant grant where grant.resourceCode in ?1")
  void deleteAllByResourceCodes(Collection<String> resourceCodes);

  @Override
  @Modifying
  @Query("delete from RoleResourceGrant grant where grant.roleId = ?1 and grant.resourceCode in ?2")
  void unauthorized(String roleId, Set<String> resourceCodes);

  @Override
  List<RoleResourceGrant> findByRoleIdIn(Collection<String> roleIds);

  @Override
  @Query("""
      select distinct grant.tenantId
      from RoleResourceGrant grant
      where grant.resourceCode in ?1
        and grant.tenantId is not null
        and grant.tenantId <> ''
      """)
  Set<String> findTenantIdsByResourceCodes(Collection<String> resourceCodes);

  @Override
  Optional<RoleResourceGrant> findFirstByTenantIdAndRoleId(String tenantId, String roleId);

  @Override
  @Modifying
  @Query("update RoleResourceGrant grant set grant.dataScopeId = :dataScopeId, grant.fieldScopeId = :fieldScopeId "
      + "where grant.tenantId = :tenantId and grant.roleId = :roleId")
  void updateScopeForRole(@Param("tenantId") String tenantId, @Param("roleId") String roleId,
                          @Param("dataScopeId") String dataScopeId, @Param("fieldScopeId") String fieldScopeId);

  @Override
  @Modifying
  @Query("update RoleResourceGrant grant set grant.resourceCode = ?2 where grant.resourceCode = ?1")
  void updateResourceCode(String oldCode, String newCode);
}
