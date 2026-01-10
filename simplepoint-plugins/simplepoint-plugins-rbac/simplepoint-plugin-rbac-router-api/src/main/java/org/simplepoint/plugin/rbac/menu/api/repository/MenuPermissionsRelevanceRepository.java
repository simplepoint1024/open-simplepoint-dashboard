package org.simplepoint.plugin.rbac.menu.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.rbac.menu.api.entity.MenuPermissionsRelevance;

/**
 * MenuPermissionsRelevanceRepository provides an interface for managing MenuPermissionsRelevance entities.
 * It is used to interact with the persistence layer for MenuPermissionsRelevance entities.
 */
public interface MenuPermissionsRelevanceRepository {

  /**
   * Save all MenuPermissionsRelevance entities in the given iterable.
   *
   * @param entities an iterable of MenuPermissionsRelevance entities to be saved
   * @param <S>      a type that extends MenuPermissionsRelevance
   * @return a list of saved MenuPermissionsRelevance entities
   */
  <S extends MenuPermissionsRelevance> List<S> saveAll(Iterable<S> entities);

  /**
   * Delete all MenuPermissionsRelevance entities associated with the specified menu authority.
   *
   * @param menuAuthority the menu authority whose associated MenuPermissionsRelevance entities are to be deleted
   */
  void deleteAllByPermissionId(String menuAuthority);

  /**
   * Delete all MenuPermissionsRelevance entities associated with the specified collection of menu authorities.
   *
   * @param menuIds a collection of menu authorities whose associated MenuPermissionsRelevance entities are to be deleted
   */
  void deleteAllByMenuIds(Collection<String> menuIds);

  /**
   * Remove specific authorities from a menu identified by menu authority.
   *
   * @param menuAuthority the menu authority of the menu
   * @param authorities   a set of authorities to be removed from the menu
   */
  void unauthorized(String menuAuthority, Set<String> authorities);

  /**
   * Retrieve all permission authorities associated with the specified menu authority.
   *
   * @param menuAuthority the menu authority whose associated permission authorities are to be retrieved
   * @return a collection of permission authorities associated with the specified menu authority
   */
  Collection<String> authorized(String menuAuthority);

  /**
   * Get all menu permissions authorities associated with the given permission authorities.
   *
   * @param permissionIds a collection of permission permissionIds
   * @return a collection of menu permissions authorities associated with the given permission authorities
   */
  Collection<String> findAllMenuIdByPermissionIds(Collection<String> permissionIds);

  /**
   * Authorize a set of MenuPermissionsRelevance entities.
   *
   * @param menuPermissionsRelevances a set of MenuPermissionsRelevance entities to be authorized
   */
  void authorize(Collection<MenuPermissionsRelevance> menuPermissionsRelevances);
}
