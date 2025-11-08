package org.simplepoint.plugin.rbac.core.api.repository;

import java.util.List;
import java.util.Set;
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
   * Delete all UserRoleRelevance entities associated with the specified username.
   *
   * @param username the username whose associated UserRoleRelevance entities are to be deleted
   */
  void deleteAllByUsername(String username);

  /**
   * Remove specific authorities from a user identified by username.
   *
   * @param username    the username of the user
   * @param authorities a set of authorities to be removed from the user
   */
  void unauthorized(String username, Set<String> authorities);
}
