package org.simplepoint.plugin.rbac.tenant.api.error;

/** Contract exception that keeps remote callers independent of persistence details. */
public class ManagedTenantResourceException extends RuntimeException {

  private final ManagedTenantResourceErrorCode errorCode;

  /** Creates a domain failure with its stable diagnostic code. */
  public ManagedTenantResourceException(
      final ManagedTenantResourceErrorCode errorCode,
      final String message
  ) {
    super(message);
    this.errorCode = errorCode;
  }

  /** Retains an internal cause while preserving the public domain code. */
  public ManagedTenantResourceException(
      final ManagedTenantResourceErrorCode errorCode,
      final String message,
      final Throwable cause
  ) {
    super(message, cause);
    this.errorCode = errorCode;
  }

  /** Returns the stable domain diagnostic code. */
  public ManagedTenantResourceErrorCode errorCode() {
    return errorCode;
  }
}
