package org.simplepoint.plugin.rbac.core.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.core.authority.RoleGrantedAuthority;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.UserPickerItem;
import org.simplepoint.security.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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
   * Loads users for trusted internal profile decoration without applying the active tenant view.
   *
   * @param userIds user IDs to load
   * @return active users matching the IDs
   */
  Collection<User> findAllByIdsForAuthorization(Collection<String> userIds);

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

  /**
   * Searches enabled users by an email or phone-number prefix for remote picker fields.
   *
   * @param keyword normalized email or phone-number prefix
   * @param pageable bounded page request
   * @return matching lightweight users
   */
  Page<UserPickerItem> searchPickerItems(String keyword, Pageable pageable);

  /**
   * Resolves already selected user IDs without running a directory search.
   *
   * @param userIds selected user IDs
   * @return lightweight users matching the IDs
   */
  Collection<UserPickerItem> findPickerItemsByIds(Collection<String> userIds);
}
