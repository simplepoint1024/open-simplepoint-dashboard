package org.simplepoint.api.security.service;

import java.util.Collection;
import org.simplepoint.api.base.BaseDetailsService;
import org.simplepoint.api.security.base.BaseRole;

/**
 * Role details service.
 */
public interface RoleDetailsService extends BaseDetailsService {

  /**
   * Load role by username.
   *
   * @param username username.
   * @param <R>      role type.
   * @return roles.
   */
  <R extends BaseRole> Collection<R> loadRoleByUsername(String username);
}
