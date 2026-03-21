package org.simplepoint.plugin.rbac.tenant.repository;

import java.util.Collection;
import java.util.Set;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantUserRelevance;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantUserRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.vo.UserRelevanceVo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Jpa repository for tenant user relations.
 */
@Repository
public interface JpaTenantUserRelevanceRepository
    extends JpaRepository<TenantUserRelevance, String>, TenantUserRelevanceRepository {

  @Override
  @Modifying
  @Query("delete from TenantUserRelevance tur where tur.tenantId = ?1 and tur.userId in ?2")
  void unauthorized(String tenantId, Set<String> userIds);

  @Override
  @Query("select distinct tur.userId from TenantUserRelevance tur where tur.tenantId = ?1")
  Collection<String> authorized(String tenantId);

  @Override
  @Modifying
  @Query("delete from TenantUserRelevance tur where tur.tenantId in ?1")
  void deleteAllByTenantIds(Collection<String> tenantIds);

  @Override
  @Query("""
      select new org.simplepoint.plugin.rbac.tenant.api.vo.UserRelevanceVo(
          u.id,
          coalesce(u.nickname, u.name, u.email, u.phoneNumber, u.id),
          u.email,
          u.phoneNumber
      )
      from User u
      order by coalesce(u.nickname, u.name, u.email, u.phoneNumber, u.id), u.id
      """)
  Page<UserRelevanceVo> items(Pageable pageable);

  @Override
  @Query("select u.id from User u where u.id in ?1")
  Set<String> existingUserIds(Collection<String> userIds);
}
