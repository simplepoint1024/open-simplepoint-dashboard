package org.simplepoint.plugin.rbac.tenant.api.constants;

/** Stable synthetic context identifiers exposed by the workspace switcher. */
public final class TenantContextIds {

  /** Selects the platform control-plane without binding the request to a tenant row. */
  public static final String PLATFORM = "__platform__";

  private TenantContextIds() {
  }
}
