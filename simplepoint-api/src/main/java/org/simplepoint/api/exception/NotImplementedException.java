package org.simplepoint.api.exception;

import java.io.Serial;

/**
 * Exception thrown when a feature is not implemented.
 *
 * <p>This exception is used to indicate that a certain feature or functionality has not been
 * implemented yet. It can be used to signal incomplete features during development or testing.</p>
 */
public class NotImplementedException extends RuntimeException {
  @Serial
  private static final long serialVersionUID = 1L;

  /**
   * Constructs a new NotImplementedException with a default message.
   */
  public NotImplementedException() {
    super("This feature is not implemented yet.");
  }

  /**
   * Constructs a new NotImplementedException with the specified detail message.
   *
   * @param message the detail message.
   */
  public NotImplementedException(String message) {
    super(message);
  }

  /**
   * Constructs a new NotImplementedException with the specified detail message and cause.
   *
   * @param message the detail message.
   * @param cause   the cause of the exception.
   */
  public NotImplementedException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Constructs a new NotImplementedException with the specified cause.
   *
   * @param cause the cause of the exception.
   */
  public NotImplementedException(Throwable cause) {
    super(cause);
  }
}
