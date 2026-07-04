package org.simplepoint.plugin.rbac.core.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.core.authority.RoleGrantedAuthority;
import org.simplepoint.security.entity.User;

/**
 * UsersRepository provides an interface for managing User entities.
 * It extends the BaseRepository to inherit basic CRUD operations.
 * This interface is used to interact with the persistence layer for User entities.
 */
public interface UserRepository extends BaseRepository<User, String> {

  /**
   * Loads a user for authorization-context calculation using only the user ID.
   *
   * @param userId the user ID to load
   * @return the user when present
   */
  Optional<User> findByIdForAuthorization(String userId);

  /**
   * Loads the roles associated with a given userId.
   *
   * @param tenantId the tenant ID to which the user belongs
   * @param userId the userId for which to load roles
   * @return a list of role names associated with the specified userId
   */
  Collection<RoleGrantedAuthority> loadRolesByUserId(String tenantId, String userId);

  /**
   * Loads resource codes associated with the given role ids.
   *
   * @param roleIds a list of role ids
   * @return resource codes granted to the specified roles
   */
  Collection<String> loadResourcesInRoleIds(List<String> roleIds);

  /**
   * Retrieve a collection of role authorities associated with a specific userId.
   *
   * @param tenantId The tenant scope.
   * @param userId The userId to filter the role authorities.
   * @return A collection of role authorities for the given userId.
   */
  Collection<String> authorized(String tenantId, String userId);
}
