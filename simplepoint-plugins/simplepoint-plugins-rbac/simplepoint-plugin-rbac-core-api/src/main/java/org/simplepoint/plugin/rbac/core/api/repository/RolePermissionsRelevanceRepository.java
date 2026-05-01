package org.simplepoint.plugin.rbac.core.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.simplepoint.security.entity.RolePermissionsRelevance;

/**
 * RolePermissionsRelevanceRepository provides an interface for managing RolePermissionsRelevance entities.
 * It is used to interact with the persistence layer for RolePermissionsRelevance entities.
 */
public interface RolePermissionsRelevanceRepository {
  /**
   * Save all RolePermissionsRelevance entities in the given iterable.
   *
   * @param entities an iterable of RolePermissionsRelevance entities to be saved
   * @param <S>      a type that extends RolePermissionsRelevance
   * @return a list of saved RolePermissionsRelevance entities
   */
  <S extends RolePermissionsRelevance> List<S> saveAll(Iterable<S> entities);

  /**
   * Delete all RolePermissionsRelevance entities associated with the specified role authority.
   *
   * @param roleId the role authority whose associated RolePermissionsRelevance entities are to be deleted
   */
  void deleteAllByroleId(String roleId);

  /**
   * Remove specific authorities from a role identified by role authority.
   *
   * @param roleId the role authority of the role
   * @param authorities   a set of authorities to be removed from the role
   */
  void unauthorized(String roleId, Set<String> authorities);

  /**
   * Finds all role-permission relevance records for the given role IDs.
   * Used to resolve the effective data scope and field scope across a user's roles.
   *
   * @param roleIds the collection of role IDs to query
   * @return list of all matching RolePermissionsRelevance records
   */
  List<RolePermissionsRelevance> findByRoleIdIn(Collection<String> roleIds);

  /**
   * Returns the first permission relevance record for a role within a tenant,
   * used to read the current scope assignment.
   *
   * @param tenantId the tenant ID
   * @param roleId   the role ID
   * @return an optional containing the first matching record
   */
  Optional<RolePermissionsRelevance> findFirstByTenantIdAndRoleId(String tenantId, String roleId);

  /**
   * Updates the data scope and field scope for all permission records belonging to a role.
   *
   * @param tenantId    the tenant ID
   * @param roleId      the role ID
   * @param dataScopeId the new data scope ID (may be null to clear)
   * @param fieldScopeId the new field scope ID (may be null to clear)
   */
  void updateScopeForRole(String tenantId, String roleId, String dataScopeId, String fieldScopeId);
}
