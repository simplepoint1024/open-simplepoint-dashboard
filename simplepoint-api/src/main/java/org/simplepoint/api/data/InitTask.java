package org.simplepoint.api.data;

/**
 * Represents a data initialization task for a specific module.
 *
 * <p>This record encapsulates the name of the module and the task to be executed during data initialization.</p>
 *
 * @param moduleName the name of the module associated with this initialization task
 * @param task       the runnable task that performs the data initialization logic
 */
public record InitTask(
    String moduleName,
    Initializer task
) {
  /**
   * Functional interface for data initializers.
   */
  public interface Initializer {
    /**
     * Run the initialization logic.
     *
     * @throws Exception if an error occurs during initialization
     */
    void run() throws Exception;
  }
}