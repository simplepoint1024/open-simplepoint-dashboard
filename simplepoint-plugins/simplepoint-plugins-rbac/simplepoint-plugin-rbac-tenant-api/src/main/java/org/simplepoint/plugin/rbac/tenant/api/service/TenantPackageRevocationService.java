package org.simplepoint.plugin.rbac.tenant.api.service;

import org.simplepoint.plugin.rbac.tenant.api.model.TenantPackageRevocation;

/** Platform administration of assigned instances; not resource offline or role-grant deletion. */
public interface TenantPackageRevocationService {
  /** Removes this instance's unconsumed credit while retaining data and original activation receipts. */
  TenantPackageRevocation revoke(String tenantId, String instanceId);
}
