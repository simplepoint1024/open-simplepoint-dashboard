package org.simplepoint.plugin.ai.core.api.model;

/**
 * Provider operation failure whose public message is a stable error code.
 */
public class AiProviderOperationException extends RuntimeException {

  private final AiProviderErrorCode errorCode;

  /**
   * Creates a provider operation failure.
   *
   * @param errorCode stable public error code
   */
  public AiProviderOperationException(final AiProviderErrorCode errorCode) {
    super(errorCode.name());
    this.errorCode = errorCode;
  }

  /**
   * Creates a provider operation failure while retaining its private cause.
   *
   * @param errorCode stable public error code
   * @param cause     private server-side cause
   */
  public AiProviderOperationException(
      final AiProviderErrorCode errorCode,
      final Throwable cause
  ) {
    super(errorCode.name(), cause);
    this.errorCode = errorCode;
  }

  /**
   * Returns the stable public error code.
   *
   * @return provider error code
   */
  public AiProviderErrorCode getErrorCode() {
    return errorCode;
  }
}
