package org.simplepoint.plugin.rbac.tenant.repository;

import java.util.Collection;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.Organization;
import org.simplepoint.plugin.rbac.tenant.api.repository.OrganizationRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Jpa repository for organizations.
 */
@Repository
public interface JpaOrganizationRepository extends BaseRepository<Organization, String>, OrganizationRepository {

  @Override
  @Query("""
      select case when count(o) > 0 then true else false end
      from Organization o
      where o.tenantId = :tenantId
        and o.code = :code
        and o.deletedAt is null
      """)
  boolean existsByTenantIdAndCode(@Param("tenantId") String tenantId, @Param("code") String code);

  @Override
  @Query("""
      select case when count(o) > 0 then true else false end
      from Organization o
      where o.tenantId = :tenantId
        and o.code = :code
        and o.id <> :id
        and o.deletedAt is null
      """)
  boolean existsByTenantIdAndCodeAndIdNot(
      @Param("tenantId") String tenantId,
      @Param("code") String code,
      @Param("id") String id
  );

  @Override
  @Query("""
      select o
      from Organization o
      where o.id = :id
        and o.tenantId = :tenantId
        and o.deletedAt is null
      """)
  Optional<Organization> findByIdAndTenantId(@Param("id") String id, @Param("tenantId") String tenantId);

  @Override
  @Query("""
      select o
      from Organization o
      where o.id in :ids
        and o.tenantId = :tenantId
        and o.deletedAt is null
      """)
  Collection<Organization> findAllByIdsAndTenantId(
      @Param("ids") Collection<String> ids,
      @Param("tenantId") String tenantId
  );

  @Override
  @Query("""
      select o
      from Organization o
      where o.tenantId = :tenantId
        and o.deletedAt is null
      order by coalesce(o.sort, 2147483647), o.name, o.code
      """)
  Collection<Organization> findAllByTenantId(@Param("tenantId") String tenantId);

  @Override
  @Query("""
      select o.id
      from Organization o
      where o.parentId in :parentIds
        and o.tenantId = :tenantId
        and o.deletedAt is null
      """)
  Collection<String> findIdsByParentIds(
      @Param("parentIds") Collection<String> parentIds,
      @Param("tenantId") String tenantId
  );
}
