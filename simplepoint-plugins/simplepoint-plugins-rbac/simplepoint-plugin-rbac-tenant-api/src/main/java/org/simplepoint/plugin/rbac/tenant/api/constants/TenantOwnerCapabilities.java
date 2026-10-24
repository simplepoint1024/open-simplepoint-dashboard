package org.simplepoint.plugin.rbac.tenant.api.constants;

import java.util.Set;

/**
 * Stable identity-owned management capabilities; ordinary role grants cannot delegate ownership.
 */
public final class TenantOwnerCapabilities {
  public static final String RESOURCES = "workspace.resources.view";
  public static final String PACKAGES = "workspace.packages.view";
  private static final Set<String> CODES = Set.of(RESOURCES, PACKAGES);

  private TenantOwnerCapabilities() {}

  /** Applies equally to original entries and aliases/dependents through the catalog graph. */
  public static boolean contains(String code) {
    return CODES.contains(code == null ? "" : code);
  }
}
