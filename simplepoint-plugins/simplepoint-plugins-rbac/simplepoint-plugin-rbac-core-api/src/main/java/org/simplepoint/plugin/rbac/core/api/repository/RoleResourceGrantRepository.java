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

  <S extends RoleResourceGrant> List<S> saveAll(Iterable<S> entities);

  void deleteAllByResourceCodes(Collection<String> resourceCodes);

  void deleteAllByroleId(String roleId);

  void unauthorized(String roleId, Set<String> resourceCodes);

  List<RoleResourceGrant> findByRoleIdIn(Collection<String> roleIds);

  Set<String> findTenantIdsByResourceCodes(Collection<String> resourceCodes);

  Optional<RoleResourceGrant> findFirstByTenantIdAndRoleId(String tenantId, String roleId);

  void updateScopeForRole(String tenantId, String roleId, String dataScopeId, String fieldScopeId);

  void updateResourceCode(String oldCode, String newCode);
}
