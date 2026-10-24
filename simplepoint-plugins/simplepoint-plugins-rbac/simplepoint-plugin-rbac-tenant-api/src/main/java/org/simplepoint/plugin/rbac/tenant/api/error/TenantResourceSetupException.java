package org.simplepoint.plugin.rbac.tenant.api.error;

/** Bounded directory setup/synchronization failures with stable translation keys. */
public class TenantResourceSetupException extends RuntimeException {
  /** Public diagnostics; no submitted resource values are included in errors. */
  public enum Code {
    OWNER_REQUIRED, TENANT_UNAVAILABLE, CATALOG_NOT_READY, ADOPTION_REQUIRED,
    RESOURCE_UNAVAILABLE, RESOURCE_CONFLICT
  }

  private final Code code;

  /** Creates a domain error without leaking platform metadata. */
  public TenantResourceSetupException(Code code) {
    super("TENANT_RESOURCE_SETUP:" + code.name());
    this.code = code;
  }

  /** Returns the stable diagnostic code. */
  public Code code() {
    return code;
  }

  /** Returns the localized message key for public adapters. */
  public String messageKey() {
    return "tenant.resources.error." + code.name();
  }
}
