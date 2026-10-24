package org.simplepoint.plugin.rbac.tenant.api.service;

import java.util.Collection;

/** Local identity-owned authorization invalidation contract; contains no persistence component. */
public interface ResourceAuthorizationVersionService {
  /** Invalidates one explicitly affected tenant. */
  void refreshTenant(String tenantId);

  /** Invalidates an explicitly affected tenant cohort. */
  void refreshTenants(Collection<String> tenantIds);
}
