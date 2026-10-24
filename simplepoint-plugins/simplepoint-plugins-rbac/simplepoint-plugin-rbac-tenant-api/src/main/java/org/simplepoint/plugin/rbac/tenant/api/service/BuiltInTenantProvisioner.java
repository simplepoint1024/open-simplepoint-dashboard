package org.simplepoint.plugin.rbac.tenant.api.service;

import java.util.Collection;

/**
 * Maintains built-in application membership and completes explicitly staged fresh tenants.
 */
public interface BuiltInTenantProvisioner {

  /**
   * Adds newly synchronized resources to the matching built-in application.
   * Completes directories for explicitly staged fresh bootstrap tenants without issuing package credits or role grants.
   *
   * @param resourceOwner owner of the synchronized resource catalog
   * @param tenantResourceCodes organization-tenant compatible resource codes
   * @param personalResourceCodes personal-workspace compatible resource codes
   */
  void provisionApplicationResources(
      String resourceOwner,
      Collection<String> tenantResourceCodes,
      Collection<String> personalResourceCodes
  );

}
