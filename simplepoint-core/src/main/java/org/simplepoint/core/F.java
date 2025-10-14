package org.simplepoint.core;

import java.util.function.Consumer;

/**
 * Utility class providing a method to process a value with a given function.
 *
 * <p>This class is designed
 */
public class F {

  /**
   * Processes the given value using the provided function and returns the value.
   *
   * @param <T>      the type of the value
   * @param value    the value to be processed
   * @param function the function to process the value
   * @return the processed value
   */
  public static <T> T processing(T value, Consumer<T> function) {
    function.accept(value);
    return value;
  }
}
