package org.simplepoint.plugin.rbac.menu.base.repository;

import java.util.Collection;
import java.util.Set;
import org.simplepoint.plugin.rbac.menu.api.entity.MenuPermissionsRelevance;
import org.simplepoint.plugin.rbac.menu.api.repository.MenuPermissionsRelevanceRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * JpaMenuPermissionsRelevanceRepository is a JPA repository interface for managing MenuPermissionsRelevance entities.
 * It extends JpaRepository to provide CRUD operations and implements MenuPermissionsRelevanceRepository
 * for custom repository methods.
 */
@Repository
public interface JpaMenuPermissionsRelevanceRepository extends JpaRepository<MenuPermissionsRelevance, String>, MenuPermissionsRelevanceRepository {

  @Override
  @Modifying
  @Query("DELETE FROM MenuPermissionsRelevance mpr WHERE mpr.menuAuthority = ?1")
  void deleteAllByPermissionAuthority(String menuAuthority);

  @Override
  @Modifying
  @Query("DELETE FROM MenuPermissionsRelevance mpr WHERE mpr.menuAuthority = ?1 AND mpr.permissionAuthority IN ?2")
  void unauthorized(String menuAuthority, Set<String> authorities);

  @Override
  @Query("SELECT permissionAuthority FROM MenuPermissionsRelevance WHERE menuAuthority = ?1")
  Collection<String> authorized(String menuAuthority);

  @Override
  @Query("SELECT menuAuthority FROM MenuPermissionsRelevance where permissionAuthority in ?1")
  Collection<String> loadAllMenuAuthorities(Collection<String> permissionAuthorities);
}
