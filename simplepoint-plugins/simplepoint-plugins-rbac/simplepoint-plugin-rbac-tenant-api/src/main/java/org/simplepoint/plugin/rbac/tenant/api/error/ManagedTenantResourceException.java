package org.simplepoint.plugin.rbac.tenant.api.error;

/** Contract exception that keeps remote callers independent of persistence details. */
public class ManagedTenantResourceException extends RuntimeException {

  private final ManagedTenantResourceErrorCode errorCode;

  public ManagedTenantResourceException(
      final ManagedTenantResourceErrorCode errorCode,
      final String message
  ) {
    super(message);
    this.errorCode = errorCode;
  }

  public ManagedTenantResourceException(
      final ManagedTenantResourceErrorCode errorCode,
      final String message,
      final Throwable cause
  ) {
    super(message, cause);
    this.errorCode = errorCode;
  }

  public ManagedTenantResourceErrorCode errorCode() {
    return errorCode;
  }
}
