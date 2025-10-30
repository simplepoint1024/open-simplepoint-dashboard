package org.simplepoint.plugin.rbac.menu.api.repository;

import java.util.Collection;
import org.simplepoint.api.base.BaseRepository;
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
   * Finds menus associated with the specified username.
   *
   * @param username the username to search menus for
   * @return a collection of {@link Menu} entities linked to the user
   */
  Collection<Menu> findUserMenus(String username);
}
