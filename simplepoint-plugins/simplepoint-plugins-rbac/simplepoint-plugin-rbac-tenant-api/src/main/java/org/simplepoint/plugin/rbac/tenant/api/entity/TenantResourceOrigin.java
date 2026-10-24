package org.simplepoint.plugin.rbac.tenant.api.entity;

/** Provenance of a tenant catalog entry. */
public enum TenantResourceOrigin {
  PLATFORM("platform", null),
  TENANT("tenant", null),
  APP("app", "app"),
  REPORT("report", "report");

  private final String label;
  private final String managedOwner;

  TenantResourceOrigin(String label, String managedOwner) {
    this.label = label;
    this.managedOwner = managedOwner;
  }

  /** Shared label for schemas and presentation; the API value remains the enum name. */
  public String messageKey() {
    return "tenant.resources." + label;
  }

  /** Trusted business namespace, independent of the physical service/client name. */
  public String managedOwner() {
    return managedOwner;
  }

  /** Rejects undeclared namespaces instead of presenting managed resources as editable custom rows. */
  public static TenantResourceOrigin forManagedOwner(String owner) {
    for (TenantResourceOrigin origin : values()) {
      if (origin.managedOwner != null && origin.managedOwner.equals(owner)) {
        return origin;
      }
    }
    throw new IllegalArgumentException("Undeclared tenant resource owner");
  }
}
