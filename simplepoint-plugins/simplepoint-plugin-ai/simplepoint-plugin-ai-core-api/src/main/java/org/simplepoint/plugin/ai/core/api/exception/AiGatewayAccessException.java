package org.simplepoint.plugin.ai.core.api.exception;

/** Stable public-gateway authentication, permission, and rate-limit failure. */
public class AiGatewayAccessException extends RuntimeException {

  private final FailureType failureType;

  /** Creates a stable gateway access failure. */
  public AiGatewayAccessException(final FailureType failureType, final String message) {
    super(message);
    this.failureType = failureType;
  }

  /** Returns the failure category used by protocol-specific error mapping. */
  public FailureType getFailureType() {
    return failureType;
  }

  /** Public access failure categories. */
  public enum FailureType {
    AUTHENTICATION,
    PERMISSION,
    RATE_LIMIT
  }
}
