package com.simplepoint.service.router.exception;

/** A completed remote invocation rejected by its provider; never a transport retry signal. */
public final class RemoteServiceFailureException extends ServiceRouterException {

  /**
   * Retains the provider's diagnostic without allowing error codes to choose Java types.
   *
   * @param code wire error code
   * @param message provider diagnostic
   */
  public RemoteServiceFailureException(final String code, final String message) {
    super(code + ": " + message);
  }
}
