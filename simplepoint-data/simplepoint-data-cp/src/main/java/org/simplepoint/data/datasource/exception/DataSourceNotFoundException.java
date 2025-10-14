package org.simplepoint.data.datasource.exception;

/**
 * A custom exception class for handling cases where the expected data source is missing.
 * Extends {@link RuntimeException} for unchecked exceptions.
 */
public class DataSourceNotFoundException extends RuntimeException {

  /**
   * Default constructor that provides a predefined exception message.
   */
  public DataSourceNotFoundException() {
    super("The expected data source was not found!");
  }

  /**
   * Constructor that allows custom exception messages.
   *
   * @param message the custom message for the exception
   */
  public DataSourceNotFoundException(String message) {
    super(message);
  }
}
