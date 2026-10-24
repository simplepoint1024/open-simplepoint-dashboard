package org.simplepoint.security.service;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.security.ResourceDeclaration;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceNode;
import org.simplepoint.security.pojo.dto.ServiceResourceRouteResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Resource service. */
public interface ResourceService extends BaseService<Resource, String> {

  /**
   * Applies a complete declaration snapshot inside the trusted identity/plugin installation
   * boundary. This local management operation is never exported as a remoting contract.
   */
  void sync(String owner, Set<ResourceDeclaration> declarations);

  /**
   * Applies a routed declaration after the provider has verified its service identity. The domain
   * checks durable generation authority in the same transaction as its writes; this local port is
   * never exported as RPC and must not receive a request-supplied client identity.
   */
  void syncRegistered(String owner, Set<ResourceDeclaration> declarations, String verifiedClient);

  /** Removes an owner's complete catalog without cascading into another owner's resources. */
  void removeByOwner(String owner);

  /** Returns route resources visible to the current actor. */
  ServiceResourceRouteResult routes();

  /** Returns tree-shaped resources for management pages. */
  Page<ResourceNode> limitTree(Map<String, String> attributes, Pageable pageable);

  /** Returns one paged tree level for lazy resource assignment UIs. */
  Page<ResourceNode> children(Map<String, String> attributes, Pageable pageable);

  /** Returns one paged level of resources that are assigned or ancestors of assigned resources. */
  Page<ResourceNode> assignedTree(
      Collection<String> codes, Map<String, String> attributes, Pageable pageable);

  /**
   * Finds platform resource snapshots inside the current runtime scope, not the cross-scope
   * authoring directory.
   */
  Collection<Resource> findAllByCodes(Collection<String> codes);

  /**
   * Authoring snapshots: platform administrators retain every scope; runtime authorization must use
   * the explicit-scope methods.
   */
  Collection<Resource> findDirectoryResourcesByCodes(Collection<String> codes);

  /** Returns all platform resources visible inside the explicit platform authorization boundary. */
  Collection<Resource> findAllAccessible();

  /**
   * Filters resource codes for an explicitly supplied runtime scope.
   *
   * <p>This is used while an authorization context is being constructed, before that context is
   * available through the request holder.
   */
  Collection<String> filterAccessibleCodes(
      Collection<String> codes, AuthorizationScopeType scopeType, boolean systemAdministrator);

  /** Filters enabled, grantable resource codes for an explicitly supplied runtime scope. */
  Collection<String> filterGrantableAccessibleCodes(
      Collection<String> codes, AuthorizationScopeType scopeType);

  /** Platform administrator template delivery; never a role authorization expansion. */
  Collection<String> filterDistributableCodes(Collection<String> codes);

  /** Complete template delivery candidates below a root, including private execution hosts. */
  Collection<String> findSubtreeDistributableCodes(String rootId);

  /** Returns every enabled resource code available in an explicitly supplied runtime scope. */
  Collection<String> findAllAccessibleCodes(
      AuthorizationScopeType scopeType, boolean systemAdministrator);

  /** Finds grantable resource codes for the root resource and all of its descendants. */
  Collection<String> findSubtreeGrantableCodes(String rootId);

  /** Finds resource codes requiring organization tenant context. */
  Collection<String> findAllRequireOrgTenantCodes();

  /** Finds public resource codes available to all authenticated users. */
  Collection<String> findPublicAccessCodes();
}
