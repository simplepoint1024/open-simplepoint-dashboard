package org.simplepoint.plugin.workflow.service;

import java.util.function.Supplier;

/** A verified business-service invocation owns process creation for a durable business intent. */
final class WorkflowBusinessSubmissionScope {
  private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

  private WorkflowBusinessSubmissionScope() {}

  static boolean active() {
    return Boolean.TRUE.equals(ACTIVE.get());
  }

  static <T> T call(Supplier<T> operation) {
    Boolean previous = ACTIVE.get();
    ACTIVE.set(true);
    try {
      return operation.get();
    } finally {
      if (previous == null) {
        ACTIVE.remove();
      } else {
        ACTIVE.set(previous);
      }
    }
  }
}
