package org.simplepoint.plugin.rbac.tenant.repository;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.vo.NamedTenantVo;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JpaTenantRepository provides an interface for managing Tenant entities.
 * It extends the BaseRepository to inherit basic CRUD operations and the TenantRepository
 * to include specific methods for handling tenant data.
 * This interface is used to interact with the persistence layer for Tenant entities.
 */
@Repository
public interface JpaTenantRepository extends BaseRepository<Tenant, String>, TenantRepository {

  @Override
  @Query("""
      select
          new org.simplepoint.plugin.rbac.tenant.api.vo.NamedTenantVo(t.id, t.name, t.tenantType, t.logo)
      from Tenant t
      where t.ownerId = :userId
         or exists (
             select utr.id
             from TenantUserRelevance utr
             where utr.tenantId = t.id and utr.userId = :userId
         )
      order by
          case when t.tenantType = org.simplepoint.plugin.rbac.tenant.api.entity.TenantType.PERSONAL then 1 else 0 end,
          t.name
      """)
  Set<NamedTenantVo> getTenantsByUserId(@Param("userId") String userId);

  @Override
  @Query("""
      select t from Tenant t
      where t.ownerId = :ownerId
        and t.tenantType = org.simplepoint.plugin.rbac.tenant.api.entity.TenantType.PERSONAL
      """)
  Optional<Tenant> findPersonalTenantByOwnerId(@Param("ownerId") String ownerId);

  @Override
  @Query("select coalesce(t.authorizationVersion, 0) from Tenant t where t.id = :tenantId")
  Long getTenantAuthorizationVersion(@Param("tenantId") String tenantId);

  @Override
  @Modifying
  @Query("""
      update Tenant t
      set t.authorizationVersion = coalesce(t.authorizationVersion, 0) + 1
      where t.id in :tenantIds
      """)
  void increaseAuthorizationVersion(@Param("tenantIds") Collection<String> tenantIds);

  @Override
  @Query("select t.id from Tenant t")
  Collection<String> findAllIds();

  @Override
  @Query("""
      select case when count(t) > 0 then true else false end
      from Tenant t
      where t.id = :tenantId
        and (
            t.ownerId = :userId
            or exists (
                select utr.id
                from TenantUserRelevance utr
                where utr.tenantId = t.id and utr.userId = :userId
            )
        )
      """)
  boolean hasUser(@Param("tenantId") String tenantId, @Param("userId") String userId);

  @Override
  @Query("""
      select case when count(t) > 0 then true else false end
      from Tenant t
      where lower(t.name) = lower(:name)
        and t.id <> :tenantId
        and t.deletedAt is null
      """)
  boolean existsByNameAndIdNot(@Param("name") String name, @Param("tenantId") String tenantId);
}
