package org.simplepoint.plugin.rbac.tenant.api.service;

import org.simplepoint.plugin.rbac.tenant.api.model.TenantPackageActivation;

/** Local identity-domain operation, scoped to the current tenant's actual owner. */
public interface TenantPackageActivationService {
  /** Atomically activates a pending instance or returns the already committed receipt. */
  TenantPackageActivation activate(String instanceId);
}
