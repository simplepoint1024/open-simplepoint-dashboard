package org.simplepoint.plugin.rbac.core.api.repository;

import java.util.Collection;
import java.util.List;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.security.entity.RolePermissionsRelevance;
import org.simplepoint.security.entity.User;

/**
 * UsersRepository provides an interface for managing User entities.
 * It extends the BaseRepository to inherit basic CRUD operations.
 * This interface is used to interact with the persistence layer for User entities.
 */
public interface UserRepository extends BaseRepository<User, String> {
  /**
   * Loads the roles associated with a given username.
   *
   * @param username the username for which to load roles
   * @return a list of role names associated with the specified username
   */
  List<String> loadRolesByUsername(String username);

  /**
   * Loads permissions associated with the given role authorities.
   *
   * @param roleAuthorities a list of role authorities for which to load permissions
   * @return a list of SimplePermissions associated with the specified role authorities
   */
  List<RolePermissionsRelevance> loadPermissionsInRoleAuthorities(List<String> roleAuthorities);

  /**
   * Retrieve a collection of role authorities associated with a specific username.
   *
   * @param username The username to filter the role authorities.
   * @return A collection of role authorities for the given username.
   */
  Collection<String> authorized(String username);
}
