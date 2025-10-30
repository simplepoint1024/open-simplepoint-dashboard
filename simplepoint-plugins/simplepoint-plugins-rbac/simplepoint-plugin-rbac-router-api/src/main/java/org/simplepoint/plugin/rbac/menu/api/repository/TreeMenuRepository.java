package org.simplepoint.plugin.rbac.menu.api.repository;

import java.util.Collection;
import org.simplepoint.security.entity.Menu;

/**
 * Repository interface for managing TreeMenu entities.
 * This interface can be extended to provide CRUD operations
 * for TreeMenu entities.
 */
public interface TreeMenuRepository {

  /**
   * Finds menus whose paths end with any of the specified suffixes.
   *
   * @param pathSuffixes a collection of path suffixes to search for
   * @return a collection of menus matching the specified path suffixes
   */
  Collection<Menu> findInPathStartingWith(Collection<String> pathSuffixes);
}
