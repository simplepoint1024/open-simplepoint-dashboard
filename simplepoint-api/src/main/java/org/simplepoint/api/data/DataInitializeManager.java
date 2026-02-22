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
  boolean execute(String moduleName, InitTask.Initializer initializer);


}
