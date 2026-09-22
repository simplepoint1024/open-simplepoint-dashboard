package org.simplepoint.core.query;

/** Invalid client query; messages must not contain submitted values or database details. */
public class InvalidQueryException extends IllegalArgumentException {
  /** Creates a client-safe validation failure. */
  public InvalidQueryException(String message) {
    super(message);
  }
}
