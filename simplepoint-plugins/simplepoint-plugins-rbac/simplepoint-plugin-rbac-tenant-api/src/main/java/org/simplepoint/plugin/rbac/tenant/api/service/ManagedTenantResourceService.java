package org.simplepoint.plugin.rbac.tenant.api.service;

import org.simplepoint.plugin.rbac.tenant.api.model.ManagedTenantResourceCommand;
import org.simplepoint.plugin.rbac.tenant.api.model.ManagedTenantResourceView;
import org.simplepoint.remoting.RemoteContract;

/** Trusted service contract for tenant-scoped dynamic resource lifecycles. */
@RemoteContract(name = "saas.managed-resource", version = "1")
public interface ManagedTenantResourceService {

  /** Creates or refreshes a declaration without exposing a new resource prematurely. */
  ManagedTenantResourceView prepare(ManagedTenantResourceCommand command);

  /** Enables a prepared resource and its prepared descendants. */
  ManagedTenantResourceView activate(String tenantId, String managedKey);

  /** Activates a newer publication without resurrecting grants removed by formal offline. */
  ManagedTenantResourceView activateGeneration(String tenantId, String managedKey, long generation);

  /** Publishes the page and its trusted permission descriptor under the same generation fence. */
  ManagedTenantResourceView activateWithPermissions(String tenantId, String managedKey, long generation,
      org.simplepoint.core.permission.PermissionPageDescriptor permissions);

  /** Reconciles an existing active page without enabling resources or restoring grants. */
  ManagedTenantResourceView refreshPermissions(String tenantId, String managedKey, long generation,
      org.simplepoint.core.permission.PermissionPageDescriptor permissions);

  /** Disables a resource and its descendants while preserving grants for later recovery. */
  ManagedTenantResourceView deactivate(String tenantId, String managedKey);

  /** Resolves a resource after an uncertain remote response. */
  ManagedTenantResourceView get(String tenantId, String managedKey);
}
