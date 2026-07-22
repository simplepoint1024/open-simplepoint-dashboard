package org.simplepoint.plugin.rbac.core.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.simplepoint.security.entity.RoleResourceGrant;

/**
 * Repository for role resource grants.
 */
public interface RoleResourceGrantRepository {

  /**
   * Persists all supplied role resource grants.
   *
   * @param entities grants to persist
   * @param <S> grant subtype
   * @return persisted grants
   */
  <S extends RoleResourceGrant> List<S> saveAll(Iterable<S> entities);

  /** Deletes every grant associated with one of the resource codes. */
  void deleteAllByResourceCodes(Collection<String> resourceCodes);

  /** Deletes every grant assigned to the role. */
  void deleteAllByroleId(String roleId);

  /** Removes the specified resource grants from the role. */
  void unauthorized(String roleId, Set<String> resourceCodes);

  /** Returns grants assigned to any of the supplied roles. */
  List<RoleResourceGrant> findByRoleIdIn(Collection<String> roleIds);

  /** Returns tenant identifiers referenced by the supplied resource codes. */
  Set<String> findTenantIdsByResourceCodes(Collection<String> resourceCodes);

  /** Returns one matching grant for the tenant and role, when present. */
  Optional<RoleResourceGrant> findFirstByTenantIdAndRoleId(String tenantId, String roleId);

  /** Updates the data and field scopes for every grant assigned to the role. */
  void updateScopeForRole(String tenantId, String roleId, String dataScopeId, String fieldScopeId);

  /** Replaces an existing resource code in all grants. */
  void updateResourceCode(String oldCode, String newCode);
}
