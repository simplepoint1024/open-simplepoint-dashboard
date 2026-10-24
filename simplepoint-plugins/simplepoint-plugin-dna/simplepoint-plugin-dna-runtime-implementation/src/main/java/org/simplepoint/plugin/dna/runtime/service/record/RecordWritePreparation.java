package org.simplepoint.plugin.dna.runtime.service.record;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;

/** A write plan built while the physical transaction and its admission locks remain active. */
record RecordWritePreparation(List<RecordSqlStatement> statements, RecordSnapshotPlan snapshot, int receiptCount) {
  RecordWritePreparation(final List<RecordSqlStatement> statements, final RecordSnapshotPlan snapshot) {
    this(statements, snapshot, 1);
  }

  RecordWritePreparation {
    statements = List.copyOf(statements);
    if (statements.isEmpty() || statements.size() > 401 || receiptCount < 1 || receiptCount > 100 || receiptCount > statements.size()) {
      throw new IllegalArgumentException("Prepared record write exceeds its mutation budget");
    }
  }

  /** The reader borrows the same Calcite transaction; work cannot commit, close or replace it. */
  @FunctionalInterface
  interface Work {
    RecordWritePreparation prepare(Function<RecordSelectPlan, CalciteQueryResult> reader, Instant at, RecordCalculationStore.Installed calculation);
  }
}
