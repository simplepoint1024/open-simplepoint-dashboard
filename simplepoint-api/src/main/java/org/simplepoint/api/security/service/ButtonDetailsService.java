package org.simplepoint.api.security.service;

import java.util.Collection;
import org.simplepoint.api.base.BaseDetailsService;
import org.simplepoint.api.security.base.BaseButton;

/**
 * Button Details Service.
 */
public interface ButtonDetailsService extends BaseDetailsService {
  /**
   * Get button by permissions authority.
   *
   * @param permissionsAuthority permissions authority.
   * @param <B>                  button type.
   * @return button.
   */
  <B extends BaseButton> Collection<B> loadButtonByPermissionsAuthority(String permissionsAuthority);
}
