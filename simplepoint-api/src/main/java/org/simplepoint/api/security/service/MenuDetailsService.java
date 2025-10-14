package org.simplepoint.api.security.service;

import java.util.Collection;
import org.simplepoint.api.base.BaseDetailsService;
import org.simplepoint.api.security.base.BaseMenu;

/**
 * Menu Details Service.
 */
public interface MenuDetailsService extends BaseDetailsService {

  /**
   * Get menu by permissions authority.
   *
   * @param permissionsAuthority permissions authority.
   * @param <M>                  menu type.
   * @return menu.
   */
  <M extends BaseMenu> Collection<M> loadMenuByPermissionsAuthority(String permissionsAuthority);
}
