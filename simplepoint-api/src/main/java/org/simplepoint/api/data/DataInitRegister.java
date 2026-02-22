package org.simplepoint.api.data;

/**
 * Interface for registering data initialization tasks.
 *
 * <p>This interface provides methods to register data initialization tasks for specific modules or without specifying a module name.
 * Implementations of this interface can be used to manage and execute data initialization tasks during application startup or at specific points in the application lifecycle.
 */
@FunctionalInterface
public interface DataInitRegister {
  /**
   * Registers a data initialization task for a specific module.
   *
   * @return an instance of InitTask representing the registered task
   */
  InitTask register();
}
