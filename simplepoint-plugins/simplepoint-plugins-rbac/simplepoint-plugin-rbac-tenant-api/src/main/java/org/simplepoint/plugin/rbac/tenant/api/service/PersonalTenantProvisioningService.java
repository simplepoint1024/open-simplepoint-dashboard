package org.simplepoint.plugin.rbac.tenant.api.service;

import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;

/** Local identity account lifecycle port, never a browser or RPC account-selection operation. */
public interface PersonalTenantProvisioningService {
  /** Returns a prepared personal workspace, creating it atomically for a persisted, nondeleted account. */
  Tenant ensureForUser(String userId);
}
