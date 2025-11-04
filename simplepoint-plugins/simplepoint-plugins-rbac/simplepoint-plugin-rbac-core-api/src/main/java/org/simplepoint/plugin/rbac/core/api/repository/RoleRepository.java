package org.simplepoint.plugin.rbac.core.api.repository;

import java.util.Collection;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.RolePermissionsRelevance;

/**
 * RolesRepository provides an interface for managing Role entities.
 * It extends the BaseRepository to inherit basic CRUD operations.
 * This interface is used to interact with the persistence layer for Role entities.
 */
public interface RoleRepository extends BaseRepository<Role, String> {

  /**
   * Load role-permission relationships based on a collection of role authorities.
   *
   * @param roleAuthorities A collection of role authorities to filter the relationships.
   * @return A collection of RolePermissionsRelevance entities associated with the given role authorities.
   */
  Collection<RolePermissionsRelevance> loadPermissionsByRoleAuthorities(Collection<String> roleAuthorities);
}
