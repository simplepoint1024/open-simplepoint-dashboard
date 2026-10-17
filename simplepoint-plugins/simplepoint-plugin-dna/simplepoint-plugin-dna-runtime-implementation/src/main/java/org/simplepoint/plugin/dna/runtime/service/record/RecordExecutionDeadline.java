package org.simplepoint.plugin.dna.runtime.service.record;

import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;

/** One monotonic budget across lock waiting, multiple inspections and the final version update. */
final class RecordExecutionDeadline {

  private final long startedAt = System.nanoTime();
  private final long budgetNanos;

  RecordExecutionDeadline(final int timeoutMs) {
    if (timeoutMs < 1 || timeoutMs > 120_000) {
      throw new IllegalArgumentException("Invalid record execution budget");
    }
    budgetNanos = timeoutMs * 1_000_000L;
  }

  int remainingMillis() {
    long remaining = budgetNanos - (System.nanoTime() - startedAt);
    if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
      throw new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED, "Record execution budget was exhausted");
    }
    return (int) Math.max(1, remaining / 1_000_000L);
  }
}
