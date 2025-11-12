package org.simplepoint.plugin.rbac.menu.api.repository;

import java.util.Collection;
import java.util.List;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.rbac.menu.api.entity.MenuPermissionsRelevance;
import org.simplepoint.security.entity.Menu;

/**
 * Repository interface for {@link Menu} entity.
 *
 * <p>Provides data access operations for menus, extending {@link BaseRepository}
 * to support standard CRUD functionality.</p>
 *
 * @author JinxuLiu
 * @since 1.0
 */
public interface MenuRepository extends BaseRepository<Menu, String> {
  /**
   * Load menu-permission relevances by a collection of permission authorities.
   *
   * @param permissionAuthorities collection of permission authorities
   * @return collection of {@link MenuPermissionsRelevance} entities
   */
  Collection<MenuPermissionsRelevance> loadPermissionsByPermissionAuthorities(Collection<String> permissionAuthorities);

  /**
   * Load menus by a collection of menu IDs.
   *
   * @param ids collection of menu IDs
   * @return collection of {@link Menu} entities
   */
  Collection<Menu> loadByIds(Collection<String> ids);

  /**
   * Load all menus.
   *
   * @return SQL Connection containing all {@link Menu} entities
   */
  Collection<Menu> loadAll();

  /**
   * Load menu IDs by a list of menu authorities.
   *
   * @param menuAuthorities list of menu authorities
   * @return list of menu IDs
   */
  List<String> loadMenuIdsByAuthorities(List<String> menuAuthorities);
}
