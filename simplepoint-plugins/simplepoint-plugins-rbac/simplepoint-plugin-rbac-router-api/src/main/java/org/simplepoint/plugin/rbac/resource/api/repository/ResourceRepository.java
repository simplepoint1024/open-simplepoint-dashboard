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

  /**
   * Loads resources by ids without tenant filtering.
   */
  Collection<Resource> loadByIds(Collection<String> ids);

  /**
   * Loads all resources without tenant filtering.
   */
  Collection<Resource> loadAll();

  /**
   * Finds resources by stable codes.
   */
  Collection<Resource> findAllByCodes(Collection<String> codes);

  /**
   * Finds direct children for a parent resource.
   */
  Page<Resource> findChildren(Pageable pageable, String parentId, String keyword);

  /**
   * Finds direct children inside a constrained id set.
   */
  Page<Resource> findChildrenByIds(Pageable pageable, Collection<String> ids, String parentId);

  /**
   * Finds resources matching a keyword.
   */
  Page<Resource> findMatches(Pageable pageable, String keyword);

  /**
   * Finds parent ids that have child resources.
   */
  Collection<String> findParentIdsWithChildren(Collection<String> parentIds);

  /**
   * Finds parent ids that have child resources inside a constrained id set.
   */
  Collection<String> findParentIdsWithChildrenIn(Collection<String> ids, Collection<String> parentIds);

  /**
   * Finds resource codes that require organization tenant context.
   */
  Collection<String> findCodesByRequireOrgTenant();

  /**
   * Finds public resource codes.
   */
  Collection<String> findPublicAccessCodes();

  /**
   * Finds grantable resource codes within tenant package resource codes.
   */
  Collection<String> findGrantableCodesByTenantResourceCodes(Collection<String> codes);

  /**
   * Finds grantable resources constrained by codes.
   */
  Page<Resource> findGrantable(Pageable pageable, Collection<String> codes);

  /**
   * Finds all grantable resources.
   */
  Page<Resource> findGrantableAll(Pageable pageable);

  /**
   * Finds resource ids by stable codes.
   */
  Collection<String> findIdsByCodes(Collection<String> codes);

  /**
   * Finds route resources by codes.
   */
  Collection<Resource> findRouteResourcesByCodes(Collection<String> codes);

  /**
   * Finds all route resources.
   */
  Collection<Resource> findRouteResourcesAll();

  /**
   * Finds resource codes by resource types.
   */
  Collection<String> findCodesByTypes(Collection<ResourceType> types);
}
