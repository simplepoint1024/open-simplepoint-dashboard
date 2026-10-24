package org.simplepoint.plugin.rbac.tenant.api.service;

/** Local identity lifecycle service. Initialization is invoked only by trusted tenant creation paths. */
public interface TenantResourceProvisioningService {
  /** Prepares a new tenant's baseline directory in the caller's creation transaction. */
  void initialize(String tenantId);

  /** Previews current platform definitions without changing resources, grants or time entitlements. */
  TenantResourceCatalog.SyncPreview preview(boolean overwrite);

  /** Imports current platform definitions for the verified current owner; it grants no package time. */
  TenantResourceCatalog.SyncResult synchronize(boolean overwrite);
}
