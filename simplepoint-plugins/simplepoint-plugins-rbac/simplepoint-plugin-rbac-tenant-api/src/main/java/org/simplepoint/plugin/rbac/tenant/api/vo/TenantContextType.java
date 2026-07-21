package org.simplepoint.plugin.rbac.tenant.api.vo;

import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;

/** Type of workspace exposed by the context selector, including the synthetic platform entry. */
public enum TenantContextType {
  PLATFORM,
  PERSONAL,
  ORGANIZATION;

  /** Maps a persisted tenant type to its workspace representation. */
  public static TenantContextType from(final TenantType tenantType) {
    return tenantType == null ? null : TenantContextType.valueOf(tenantType.name());
  }
}
