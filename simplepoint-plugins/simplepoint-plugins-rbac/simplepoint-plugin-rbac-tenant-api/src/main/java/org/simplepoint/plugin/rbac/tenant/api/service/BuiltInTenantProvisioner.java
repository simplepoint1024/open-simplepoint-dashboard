package org.simplepoint.plugin.rbac.tenant.api.service;

import java.util.Collection;

/**
 * Keeps built-in tenant entitlements aligned with the resource catalog and tenant lifecycle.
 */
public interface BuiltInTenantProvisioner {

  /**
   * Adds newly synchronized resources to the matching built-in application.
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

  /**
   * Assigns the built-in personal package to a personal tenant when available.
   *
   * @param tenantId personal tenant identifier
   */
  void provisionPersonalTenant(String tenantId);
}
