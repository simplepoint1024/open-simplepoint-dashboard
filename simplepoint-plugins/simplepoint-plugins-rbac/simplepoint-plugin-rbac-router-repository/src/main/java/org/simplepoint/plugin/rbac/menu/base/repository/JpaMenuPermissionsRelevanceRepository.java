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
  @Query("DELETE FROM MenuPermissionsRelevance mpr WHERE mpr.menuId = ?1")
  void deleteAllByPermissionId(String menuId);

  @Override
  @Modifying
  @Query("DELETE FROM MenuPermissionsRelevance mpr WHERE mpr.menuId IN ?1")
  void deleteAllByMenuIds(Collection<String> menuId);

  @Override
  @Modifying
  @Query("DELETE FROM MenuPermissionsRelevance mpr WHERE mpr.menuId = ?1 AND mpr.permissionId IN ?2")
  void unauthorized(String menuId, Set<String> authorities);

  @Override
  @Query("SELECT permissionId FROM MenuPermissionsRelevance WHERE menuId = ?1")
  Collection<String> authorized(String menuId);

  @Override
  @Query("SELECT menuId FROM MenuPermissionsRelevance where permissionId in ?1")
  Collection<String> findAllMenuIdByPermissionIds(Collection<String> permissionIds);

  @Override
  default void authorize(Collection<MenuPermissionsRelevance> menuPermissionsRelevances) {
    this.saveAll(menuPermissionsRelevances);
  }
}
