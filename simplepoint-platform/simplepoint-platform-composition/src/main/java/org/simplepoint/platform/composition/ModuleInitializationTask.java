package org.simplepoint.platform.composition;

import java.util.Objects;

/**
 * One prepared startup action; equal priorities preserve module dependency and declaration order.
 *
 * @param order priority within the initializer's stage, lowest first
 * @param action prepared, restartable work bound to its owning module
 */
public record ModuleInitializationTask(int order, Runnable action) {
  /** Rejects a missing action before any prepared contribution executes. */
  public ModuleInitializationTask {
    Objects.requireNonNull(action, "Module initialization action");
  }
}
