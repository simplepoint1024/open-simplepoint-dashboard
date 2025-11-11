package org.simplepoint.plugin.rbac.core.api.repository;

import java.util.List;
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
   * @param roleAuthority the role authority whose associated RolePermissionsRelevance entities are to be deleted
   */
  void deleteAllByRoleAuthority(String roleAuthority);

  /**
   * Remove specific authorities from a role identified by role authority.
   *
   * @param roleAuthority the role authority of the role
   * @param authorities   a set of authorities to be removed from the role
   */
  void unauthorized(String roleAuthority, Set<String> authorities);
}
