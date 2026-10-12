package org.simplepoint.plugin.rbac.tenant.repository;

import static org.simplepoint.security.identity.UserDisplayName.HQL;

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
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Jpa repository for tenant user relations.
 */
@Repository
public interface JpaTenantUserRelevanceRepository
    extends JpaRepository<TenantUserRelevance, String>, TenantUserRelevanceRepository {

  @Override
  @org.springframework.transaction.annotation.Transactional
  default void unauthorized(String tenantId, Set<String> userIds) {
    if (userIds == null || userIds.isEmpty()) return;
    deleteMemberRoles(tenantId, userIds);
    deleteMemberships(tenantId, userIds);
  }

  @Modifying
  @Query("delete from UserRoleRelevance r where r.tenantId = ?1 and r.userId in ?2")
  void deleteMemberRoles(String tenantId, Set<String> userIds);

  @Modifying
  @Query("delete from TenantUserRelevance tur where tur.tenantId = ?1 and tur.userId in ?2")
  void deleteMemberships(String tenantId, Set<String> userIds);

  @Override
  @Query("select distinct tur.userId from TenantUserRelevance tur where tur.tenantId = ?1 and tur.deletedAt is null")
  Collection<String> authorized(String tenantId);

  @Override
  @Query("""
      select m.userId from TenantUserRelevance m where m.tenantId=:tenantId and m.deletedAt is null
        and ((:type='SELF' and m.userId=:userId)
          or (:type in ('DEPT','DEPT_AND_BELOW','CUSTOM')
            and (m.orgId in :departments or (:includeSelf=true and m.userId=:userId))))
      """)
  Collection<String> authorizedForScope(@Param("tenantId") String tenantId, @Param("type") String type,
      @Param("departments") Set<String> departments, @Param("userId") String userId,
      @Param("includeSelf") boolean includeSelf);

  @Override
  @Modifying
  @Query("delete from TenantUserRelevance tur where tur.tenantId in ?1")
  void deleteAllByTenantIds(Collection<String> tenantIds);

  @Override
  @Query("""
      select new org.simplepoint.plugin.rbac.tenant.api.vo.UserRelevanceVo(
          u.id,
          """ + HQL + """
          ,
          u.email,
          u.phoneNumber
      )
      from User u
      order by
      """ + HQL + """
          , u.id
      """)
  Page<UserRelevanceVo> items(Pageable pageable);

  @Override
  @Query("""
      select new org.simplepoint.plugin.rbac.tenant.api.vo.UserRelevanceVo(
          u.id,
          """ + HQL + """
          ,
          u.email,
          u.phoneNumber
      )
      from User u
      join TenantUserRelevance tur on tur.userId = u.id
      where tur.tenantId = ?1
      order by
      """ + HQL + """
          , u.id
      """)
  Page<UserRelevanceVo> items(String tenantId, Pageable pageable);

  @Override
  @Query("""
      select new org.simplepoint.plugin.rbac.tenant.api.vo.UserRelevanceVo(
          u.id,
          """ + HQL + """
          ,
          u.email,
          u.phoneNumber
      )
      from User u
      where lower(u.id) like concat('%', lower(:keyword), '%')
         or lower(coalesce(u.nickname, '')) like concat('%', lower(:keyword), '%')
         or lower(
      """ + HQL + """
          ) like concat('%', lower(:keyword), '%')
         or lower(coalesce(u.email, '')) like concat('%', lower(:keyword), '%')
         or lower(coalesce(u.phoneNumber, '')) like concat('%', lower(:keyword), '%')
      order by
      """ + HQL + """
          , u.id
      """)
  Page<UserRelevanceVo> searchItems(@Param("keyword") String keyword, Pageable pageable);

  @Override
  @Query("""
      select new org.simplepoint.plugin.rbac.tenant.api.vo.UserRelevanceVo(
          u.id,
          """ + HQL + """
          ,
          u.email,
          u.phoneNumber
      )
      from User u
      join TenantUserRelevance tur on tur.userId = u.id
      where tur.tenantId = :tenantId
        and (
          lower(u.id) like concat('%', lower(:keyword), '%')
          or lower(coalesce(u.nickname, '')) like concat('%', lower(:keyword), '%')
          or lower(
      """ + HQL + """
          ) like concat('%', lower(:keyword), '%')
          or lower(coalesce(u.email, '')) like concat('%', lower(:keyword), '%')
          or lower(coalesce(u.phoneNumber, '')) like concat('%', lower(:keyword), '%')
        )
      order by
      """ + HQL + """
          , u.id
      """)
  Page<UserRelevanceVo> searchItems(
      @Param("tenantId") String tenantId,
      @Param("keyword") String keyword,
      Pageable pageable
  );

  @Override
  @Query("select u.id from User u where u.id in ?1 and u.deletedAt is null")
  Set<String> existingUserIds(Collection<String> userIds);
}
