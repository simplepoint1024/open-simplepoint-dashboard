package org.simplepoint.plugin.rbac.core.api.repository;

import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.AccessCenterUserImpactVo;
import org.simplepoint.security.entity.User;
import org.simplepoint.security.entity.UserRoleRelevance;

/**
 * UserRoleRelevanceRepository provides an interface for managing UserRoleRelevance entities.
 * It is used to interact with the persistence layer for UserRoleRelevance entities.
 */
public interface UserRoleRelevanceRepository {

  /**
   * Save all UserRoleRelevance entities in the given iterable.
   *
   * @param entities an iterable of UserRoleRelevance entities to be saved
   * @param <S>      a type that extends UserRoleRelevance
   * @return a list of saved UserRoleRelevance entities
   */
  <S extends UserRoleRelevance> List<S> saveAll(Iterable<S> entities);

  /**
   * Remove specific authorities from a user identified by username.
   *
   * @param tenantId    the tenant scope
   * @param userId    the username of the user
   * @param authorities a set of authorities to be removed from the user
   */
  void unauthorized(String tenantId, String userId, Set<String> authorities);

  /**
   * Counts users assigned to a role in the tenant scope.
   *
   * @param tenantId tenant scope
   * @param roleId role identifier
   * @return number of users assigned to the role
   */
  long countByTenantIdAndRoleId(String tenantId, String roleId);

  /**
   * Loads users assigned to a role in the tenant scope.
   *
   * @param tenantId tenant scope
   * @param roleId role identifier
   * @return assigned user summaries
   */
  List<AccessCenterUserImpactVo> findUsersByTenantIdAndRoleId(String tenantId, String roleId);

  /**
   * Load a user by their phone number or email address.
   *
   * @param phoneOrEmail the phone number or email address of the user to be loaded
   * @return the User entity corresponding to the provided phone number or email address
   */
  User loadUserByPhoneOrEmail(String phoneOrEmail);
}
