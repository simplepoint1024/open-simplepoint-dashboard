package org.simplepoint.security.service;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.security.ResourceDeclaration;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceNode;
import org.simplepoint.security.pojo.dto.ServiceResourceRouteResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Resource service.
 */
public interface ResourceService extends BaseService<Resource, String> {

  /**
   * Synchronizes resources declared by a module or plugin.
   */
  void sync(String owner, Set<ResourceDeclaration> declarations);

  /**
   * Returns route resources visible to the current actor.
   */
  ServiceResourceRouteResult routes();

  /**
   * Returns tree-shaped resources for management pages.
   */
  Page<ResourceNode> limitTree(Map<String, String> attributes, Pageable pageable);

  /**
   * Returns one paged tree level for lazy resource assignment UIs.
   */
  Page<ResourceNode> children(Map<String, String> attributes, Pageable pageable);

  /**
   * Finds resources by stable codes.
   */
  Collection<Resource> findAllByCodes(Collection<String> codes);

  /**
   * Finds resource codes requiring organization tenant context.
   */
  Collection<String> findAllRequireOrgTenantCodes();

  /**
   * Finds public resource codes available to all authenticated users.
   */
  Collection<String> findPublicAccessCodes();
}
