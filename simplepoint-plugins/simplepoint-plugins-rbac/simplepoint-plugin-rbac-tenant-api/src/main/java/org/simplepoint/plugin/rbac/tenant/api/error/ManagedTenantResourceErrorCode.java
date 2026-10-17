package org.simplepoint.plugin.rbac.tenant.api.error;

/** Stable failures exposed by the managed tenant resource contract. */
public enum ManagedTenantResourceErrorCode {
  INVALID_REQUEST,
  TENANT_NOT_FOUND,
  PARENT_NOT_FOUND,
  HOST_NOT_FOUND,
  RESOURCE_CONFLICT,
  RESOURCE_NOT_FOUND
}
