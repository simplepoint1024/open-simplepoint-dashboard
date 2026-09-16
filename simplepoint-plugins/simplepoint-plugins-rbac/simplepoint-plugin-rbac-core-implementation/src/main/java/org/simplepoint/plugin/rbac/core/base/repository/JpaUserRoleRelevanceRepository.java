package org.simplepoint.plugin.rbac.core.base.repository;

import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.AccessCenterUserImpactVo;
import org.simplepoint.plugin.rbac.core.api.repository.UserRoleRelevanceRepository;
import org.simplepoint.security.entity.User;
import org.simplepoint.security.entity.UserRoleRelevance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository interface for managing UserRoleRelevance entities.
 *
 * <p>This interface extends JpaRepository to provide CRUD operations and
 * implements UserRoleRelevanceRepository for custom query methods.
 */
@Repository
public interface JpaUserRoleRelevanceRepository extends JpaRepository<UserRoleRelevance, String>, UserRoleRelevanceRepository {

  @Override
  @Modifying
  @Query("""
      delete from UserRoleRelevance urr
      where urr.tenantId = :tenantId and urr.userId = :userId and urr.roleId in :roleIds
      """)
  void unauthorized(@Param("tenantId") String tenantId, @Param("userId") String userId, @Param("roleIds") Set<String> roleIds);

  @Override
  long countByTenantIdAndRoleId(String tenantId, String roleId);

  @Override
  @Query("""
      select new org.simplepoint.plugin.rbac.core.api.pojo.vo.AccessCenterUserImpactVo(
        u.id, u.name, u.email, u.phoneNumber
      )
      from UserRoleRelevance urr
      join User u on u.id = urr.userId
      where urr.tenantId = :tenantId and urr.roleId = :roleId
      order by u.name asc, u.email asc, u.id asc
      """)
  List<AccessCenterUserImpactVo> findUsersByTenantIdAndRoleId(
      @Param("tenantId") String tenantId,
      @Param("roleId") String roleId
  );

  @Override
  @Query("select u from User u where u.phoneNumber = :phoneOrEmail or u.email = :phoneOrEmail or u.id = :phoneOrEmail")
  User loadUserByPhoneOrEmail(@Param("phoneOrEmail") String phoneOrEmail);
}
