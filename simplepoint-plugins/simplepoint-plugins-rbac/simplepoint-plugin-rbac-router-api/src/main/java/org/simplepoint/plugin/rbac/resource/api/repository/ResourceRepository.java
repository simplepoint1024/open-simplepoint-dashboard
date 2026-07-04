package org.simplepoint.plugin.rbac.resource.api.repository;

import java.util.Collection;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Repository interface for resources.
 */
public interface ResourceRepository extends BaseRepository<Resource, String> {

  Collection<Resource> loadByIds(Collection<String> ids);

  Collection<Resource> loadAll();

  Collection<Resource> findAllByCodes(Collection<String> codes);

  Page<Resource> findChildren(Pageable pageable, String parentId, String keyword);

  Page<Resource> findMatches(Pageable pageable, String keyword);

  Collection<String> findParentIdsWithChildren(Collection<String> parentIds);

  Collection<String> findCodesByRequireOrgTenant();

  Collection<String> findPublicAccessCodes();

  Collection<String> findGrantableCodesByTenantResourceCodes(Collection<String> codes);

  Page<Resource> findGrantable(Pageable pageable, Collection<String> codes);

  Page<Resource> findGrantableAll(Pageable pageable);

  Collection<String> findIdsByCodes(Collection<String> codes);

  Collection<Resource> findRouteResourcesByCodes(Collection<String> codes);

  Collection<Resource> findRouteResourcesAll();

  Collection<String> findCodesByTypes(Collection<ResourceType> types);
}
