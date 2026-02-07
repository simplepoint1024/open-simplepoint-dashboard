package org.simplepoint.api.data;

/**
 * Manager interface for data initialization.
 */
public interface DataInitializeManager {

  /**
   * Checks if the specified module has already been initialized.
   *
   * @param moduleName the name of the module to check
   * @return true if the module has been initialized, false otherwise
   */
  boolean execute(String moduleName, Initializer initializer);

  /**
   * Functional interface for data initializers.
   */
  interface Initializer {
    /**
     * Run the initialization logic.
     *
     * @throws Exception if an error occurs during initialization
     */
    void run() throws Exception;
  }
}
