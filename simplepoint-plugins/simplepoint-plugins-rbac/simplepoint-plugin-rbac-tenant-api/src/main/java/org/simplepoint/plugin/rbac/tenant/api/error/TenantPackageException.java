package org.simplepoint.plugin.rbac.tenant.api.error;

/**
 * Stable package-domain errors; public adapters use messageKey without exposing submitted values.
 */
public class TenantPackageException extends RuntimeException {
  /** Public diagnostic categories with corresponding localized message keys. */
  public enum Code {
    OWNER_REQUIRED,
    INSTANCE_NOT_FOUND,
    INSTANCE_REVOKED,
    RESOURCE_UNAVAILABLE,
    INVALID_DURATION,
    INCONSISTENT_ACTIVATION,
    PLATFORM_ADMIN_REQUIRED,
    INVALID_REQUEST,
    TENANT_NOT_FOUND,
    PACKAGE_UNAVAILABLE,
    ALLOCATION_CONFLICT,
    RESOURCE_CONFLICT,
    INSTANCE_NOT_ACTIVATABLE,
    INSTANCE_NOT_SUPPLEMENTABLE,
    ADOPTION_NOT_REQUIRED,
    LEGACY_ASSIGNMENT_RETIRED
  }

  private final Code code;

  /** Creates a bounded failure without including user-submitted values. */
  public TenantPackageException(Code code) {
    super("TENANT_PACKAGE:" + code.name());
    this.code = code;
  }

  /** Returns the machine-readable failure category. */
  public Code code() {
    return code;
  }

  /** Returns the shared browser translation key. */
  public String messageKey() {
    return "tenant.packages.error." + code.name();
  }
}
