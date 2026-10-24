package org.simplepoint.platform.composition;

/** Reusable startup behavior explicitly selected by a module, never by classpath presence alone. */
public interface ModuleInitializer {
  /**
   * Prepares individually ordered work. Most initializers return one default-priority action;
   * bootstrap adapters override this to preserve existing cross-module contribution ordering.
   */
  default java.util.List<ModuleInitializationTask> prepareTasks(ModuleInitializationContext context)
      throws Exception {
    return java.util.List.of(new ModuleInitializationTask(0, prepare(context)));
  }

  /**
   * Reads and validates declarations without external writes. The returned action applies them.
   * Every selected initializer is prepared before any action runs; remote application is not an
   * atomic transaction across modules. Implementations must support restarting after partial
   * success.
   *
   * @param context immutable owning module and its declared client capabilities
   * @return startup action, using only the captured module clients
   * @throws Exception if required declarations cannot be prepared
   */
  Runnable prepare(ModuleInitializationContext context) throws Exception;
}
