package org.simplepoint.plugin.ai.core.api.exception;

/** Indicates that an upstream AI provider rejected a request. */
public class AiProviderRequestException extends RuntimeException {

  private final int providerStatus;

  /** Creates an upstream provider request exception. */
  public AiProviderRequestException(final int providerStatus, final String message) {
    super(message);
    this.providerStatus = providerStatus;
  }

  /** Returns the original HTTP status reported by the provider. */
  public int getProviderStatus() {
    return providerStatus;
  }
}
