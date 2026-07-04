package org.simplepoint.plugin.rbac.resource.base.repository;

import java.util.Collection;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceRepository;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for resources.
 */
@Repository
public interface JpaResourceRepository extends BaseRepository<Resource, String>, ResourceRepository {

  @Override
  @Query("select r from Resource r where r.id in :ids order by r.sort asc, r.name asc")
  Collection<Resource> loadByIds(@Param("ids") Collection<String> ids);

  @Override
  @Query("select r from Resource r order by r.sort asc, r.name asc")
  Collection<Resource> loadAll();

  @Override
  @Query("select r from Resource r where r.code in :codes order by r.sort asc, r.name asc")
  Collection<Resource> findAllByCodes(@Param("codes") Collection<String> codes);

  @Override
  @Query("select r.code from Resource r where r.requireOrgTenant = true")
  Collection<String> findCodesByRequireOrgTenant();

  @Override
  @Query("select r.code from Resource r where r.publicAccess = true")
  Collection<String> findPublicAccessCodes();

  @Override
  @Query("""
      select r.code
      from Resource r
      where r.code in :codes and r.grantable = true and r.disabled = false
      """)
  Collection<String> findGrantableCodesByTenantResourceCodes(@Param("codes") Collection<String> codes);

  @Override
  @Query("""
      select r
      from Resource r
      where r.code in :codes and r.grantable = true
      order by r.sort asc, r.name asc
      """)
  Page<Resource> findGrantable(Pageable pageable, @Param("codes") Collection<String> codes);

  @Override
  @Query("""
      select r
      from Resource r
      where r.grantable = true
      order by r.sort asc, r.name asc
      """)
  Page<Resource> findGrantableAll(Pageable pageable);

  @Override
  @Query("select r.id from Resource r where r.code in :codes")
  Collection<String> findIdsByCodes(@Param("codes") Collection<String> codes);

  @Override
  @Query("""
      select r
      from Resource r
      where r.code in :codes and r.path is not null and r.disabled = false
      order by r.sort asc, r.name asc
      """)
  Collection<Resource> findRouteResourcesByCodes(@Param("codes") Collection<String> codes);

  @Override
  @Query("""
      select r
      from Resource r
      where r.path is not null and r.disabled = false
      order by r.sort asc, r.name asc
      """)
  Collection<Resource> findRouteResourcesAll();

  @Override
  @Query("select r.code from Resource r where r.type in :types")
  Collection<String> findCodesByTypes(@Param("types") Collection<ResourceType> types);
}
