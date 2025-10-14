package org.simplepoint.api.exception;

/**
 * Exception thrown when an operation is attempted on a service that has not been properly initialized.
 */
public class NotInitializedException extends Exception {
  /**
   * Constructs a new NotInitializedException with the specified detail message.
   *
   * @param message the detail message
   */
  public NotInitializedException(String message) {
    super(message);
  }
}
