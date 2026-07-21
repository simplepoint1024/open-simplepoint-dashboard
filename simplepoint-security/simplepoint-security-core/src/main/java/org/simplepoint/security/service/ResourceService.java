package org.simplepoint.security.service;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.remoting.RemoteContract;
import org.simplepoint.security.ResourceDeclaration;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceNode;
import org.simplepoint.security.pojo.dto.ServiceResourceRouteResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Resource service.
 */
@RemoteContract(name = "security.resource")
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
   * Returns one paged level of resources that are assigned or ancestors of assigned resources.
   */
  Page<ResourceNode> assignedTree(Collection<String> codes, Map<String, String> attributes, Pageable pageable);

  /**
   * Finds resources by stable codes.
   */
  Collection<Resource> findAllByCodes(Collection<String> codes);

  /** Returns all resources visible inside the current authorization boundary. */
  Collection<Resource> findAllAccessible();

  /**
   * Filters resource codes for an explicitly supplied runtime scope.
   *
   * <p>This is used while an authorization context is being constructed, before that context is
   * available through the request holder.</p>
   */
  Collection<String> filterAccessibleCodes(
      Collection<String> codes,
      AuthorizationScopeType scopeType,
      boolean systemAdministrator
  );

  /** Filters enabled, grantable resource codes for an explicitly supplied runtime scope. */
  Collection<String> filterGrantableAccessibleCodes(
      Collection<String> codes,
      AuthorizationScopeType scopeType
  );

  /** Returns every enabled resource code available in an explicitly supplied runtime scope. */
  Collection<String> findAllAccessibleCodes(
      AuthorizationScopeType scopeType,
      boolean systemAdministrator
  );

  /**
   * Finds grantable resource codes for the root resource and all of its descendants.
   */
  Collection<String> findSubtreeGrantableCodes(String rootId);

  /**
   * Finds resource codes requiring organization tenant context.
   */
  Collection<String> findAllRequireOrgTenantCodes();

  /**
   * Finds public resource codes available to all authenticated users.
   */
  Collection<String> findPublicAccessCodes();
}
