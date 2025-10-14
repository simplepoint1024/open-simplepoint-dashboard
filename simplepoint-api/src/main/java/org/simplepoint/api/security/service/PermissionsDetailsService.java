package org.simplepoint.api.security.service;

import java.util.Collection;
import org.simplepoint.api.base.BaseDetailsService;
import org.simplepoint.api.security.base.BasePermissions;

/**
 * Permissions Details Service.
 */
public interface PermissionsDetailsService extends BaseDetailsService {
  /**
   * Get permissions by role authority.
   *
   * @param roleAuthority role authority.
   * @param <P>           permissions type.
   * @return permissions.
   */
  <P extends BasePermissions> Collection<P> loadPermissionsByRoleAuthority(String roleAuthority);
}
