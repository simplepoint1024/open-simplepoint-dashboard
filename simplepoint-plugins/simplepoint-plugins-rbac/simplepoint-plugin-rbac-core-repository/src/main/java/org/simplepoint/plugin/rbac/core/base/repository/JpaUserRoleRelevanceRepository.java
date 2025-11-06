package org.simplepoint.plugin.rbac.core.base.repository;

import org.simplepoint.plugin.rbac.core.api.repository.UserRoleRelevanceRepository;
import org.simplepoint.security.entity.UserRoleRelevance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA repository interface for managing UserRoleRelevance entities.
 *
 * <p>This interface extends JpaRepository to provide CRUD operations and
 * implements UserRoleRelevanceRepository for custom query methods.
 */
@Repository
public interface JpaUserRoleRelevanceRepository extends JpaRepository<UserRoleRelevance, String>, UserRoleRelevanceRepository {

  @Override
  void deleteAllByUsername(String username);
}
