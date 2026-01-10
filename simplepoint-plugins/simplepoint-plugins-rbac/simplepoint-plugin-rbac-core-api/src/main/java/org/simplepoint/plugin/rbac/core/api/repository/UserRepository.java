package org.simplepoint.plugin.rbac.core.api.repository;

import java.util.Collection;
import java.util.List;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.core.authority.PermissionGrantedAuthority;
import org.simplepoint.core.authority.RoleGrantedAuthority;
import org.simplepoint.security.entity.User;

/**
 * UsersRepository provides an interface for managing User entities.
 * It extends the BaseRepository to inherit basic CRUD operations.
 * This interface is used to interact with the persistence layer for User entities.
 */
public interface UserRepository extends BaseRepository<User, String> {
  /**
   * Loads the roles associated with a given userId.
   *
   * @param userId the userId for which to load roles
   * @return a list of role names associated with the specified userId
   */
  Collection<RoleGrantedAuthority> loadRolesByUserId(String userId);

  /**
   * Loads permissions associated with the given role authorities.
   *
   * @param roleIds a list of role authorities for which to load permissions
   * @return a list of SimplePermissions associated with the specified role authorities
   */
  Collection<PermissionGrantedAuthority> loadPermissionsInRoleIds(List<String> roleIds);

  /**
   * Retrieve a collection of role authorities associated with a specific userId.
   *
   * @param userId The userId to filter the role authorities.
   * @return A collection of role authorities for the given userId.
   */
  Collection<String> authorized(String userId);
}
