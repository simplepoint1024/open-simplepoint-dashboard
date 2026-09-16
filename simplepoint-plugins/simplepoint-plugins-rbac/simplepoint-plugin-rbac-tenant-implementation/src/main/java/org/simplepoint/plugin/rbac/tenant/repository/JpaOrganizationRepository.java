package org.simplepoint.plugin.rbac.tenant.repository;

import java.util.Collection;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.Organization;
import org.simplepoint.plugin.rbac.tenant.api.repository.OrganizationRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
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
        and o.parentId is null
        and o.deletedAt is null
        and (:excludeId is null or o.id <> :excludeId)
      order by coalesce(o.sort, 2147483647), o.name, o.code, o.id
      """)
  Slice<Organization> findRootOptions(
      @Param("tenantId") String tenantId,
      @Param("excludeId") String excludeId,
      Pageable pageable
  );

  @Override
  @Query("""
      select o
      from Organization o
      where o.tenantId = :tenantId
        and o.deletedAt is null
        and (:excludeId is null or o.id <> :excludeId)
      order by o.code, o.id
      """)
  Slice<Organization> findFlatOptions(
      @Param("tenantId") String tenantId,
      @Param("excludeId") String excludeId,
      Pageable pageable
  );

  @Override
  @Query("""
      select o
      from Organization o
      where o.tenantId = :tenantId
        and o.parentId = :parentId
        and o.deletedAt is null
        and (:excludeId is null or o.id <> :excludeId)
      order by coalesce(o.sort, 2147483647), o.name, o.code, o.id
      """)
  Slice<Organization> findChildOptions(
      @Param("tenantId") String tenantId,
      @Param("parentId") String parentId,
      @Param("excludeId") String excludeId,
      Pageable pageable
  );

  @Override
  @Query(value = """
      select o.*
      from simpoint_saas_organizations o
      where o.tenant_id = :tenantId
        and o.deleted_at is null
        and (cast(:excludeId as text) is null or o.id <> cast(:excludeId as text))
        and lower(o.name || ' ' || o.code) like :pattern
      order by cast(:keyword as text) <<-> lower(o.name || ' ' || o.code)
      """, nativeQuery = true)
  Slice<Organization> searchOptions(
      @Param("tenantId") String tenantId,
      @Param("keyword") String keyword,
      @Param("pattern") String pattern,
      @Param("excludeId") String excludeId,
      Pageable pageable
  );

  @Override
  @Query("""
      select distinct o.parentId
      from Organization o
      where o.parentId in :parentIds
        and o.tenantId = :tenantId
        and o.deletedAt is null
      """)
  Collection<String> findParentIdsWithChildren(
      @Param("parentIds") Collection<String> parentIds,
      @Param("tenantId") String tenantId
  );

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
