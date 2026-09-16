package org.simplepoint.plugin.ai.runtime.api.model;

/**
 * Controlled failure returned by a Tool Runtime node private API.
 */
public class RuntimeNodeOperationException extends RuntimeException {

  private final int statusCode;

  /**
   * Creates one sanitized node operation failure.
   */
  public RuntimeNodeOperationException(
      final String message,
      final int statusCode
  ) {
    super(message);
    this.statusCode = statusCode;
  }

  /**
   * Creates one sanitized node operation failure while retaining its local cause.
   */
  public RuntimeNodeOperationException(
      final String message,
      final int statusCode,
      final Throwable cause
  ) {
    super(message, cause);
    this.statusCode = statusCode;
  }

  /**
   * Returns the private API HTTP status, or zero for a transport failure.
   */
  public int getStatusCode() {
    return statusCode;
  }
}
