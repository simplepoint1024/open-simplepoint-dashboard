package com.simplepoint.service.router.exception;

/**
 * Base exception for service router failures.
 */
public class ServiceRouterException extends RuntimeException {

  /**
   * Creates a router exception.
   *
   * @param message error message
   */
  public ServiceRouterException(final String message) {
    super(message);
  }

  /**
   * Creates a router exception with a cause.
   *
   * @param message error message
   * @param cause root cause
   */
  public ServiceRouterException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
