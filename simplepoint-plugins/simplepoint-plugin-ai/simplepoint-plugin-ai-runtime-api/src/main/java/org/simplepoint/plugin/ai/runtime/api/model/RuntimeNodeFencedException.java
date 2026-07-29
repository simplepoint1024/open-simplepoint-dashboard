package org.simplepoint.plugin.ai.runtime.api.model;

/**
 * Raised when an obsolete runtime process attempts to renew a newer node generation.
 */
public class RuntimeNodeFencedException extends RuntimeException {

  /**
   * Creates the exception.
   *
   * @param message safe diagnostic
   */
  public RuntimeNodeFencedException(final String message) {
    super(message);
  }
}
