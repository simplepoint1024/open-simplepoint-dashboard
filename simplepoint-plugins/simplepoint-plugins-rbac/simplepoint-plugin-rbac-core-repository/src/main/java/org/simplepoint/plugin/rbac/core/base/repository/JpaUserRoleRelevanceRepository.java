package org.simplepoint.plugin.rbac.core.base.repository;

import java.util.Set;
import org.simplepoint.plugin.rbac.core.api.repository.UserRoleRelevanceRepository;
import org.simplepoint.security.entity.UserRoleRelevance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

  @Override
  @Modifying
  @Query("delete from UserRoleRelevance urr where urr.username = :username and urr.authority in :authority")
  void unauthorized(@Param("username") String username, @Param("authority") Set<String> authorities);
}
