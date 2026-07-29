package org.simplepoint.plugin.ai.runtime.api.model;

/**
 * Raised when a heartbeat arrives before a successful node registration.
 */
public class RuntimeNodeNotRegisteredException extends RuntimeException {

  /**
   * Creates the exception.
   *
   * @param message safe diagnostic
   */
  public RuntimeNodeNotRegisteredException(final String message) {
    super(message);
  }
}
