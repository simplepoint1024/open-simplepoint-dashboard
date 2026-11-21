package org.simplepoint.plugin.dna.runtime.service.record;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;

/** A write plan built while the physical transaction and its admission locks remain active. */
record RecordWritePreparation(
    List<RecordSqlStatement> statements,
    RecordSnapshotPlan snapshot,
    int receiptCount,
    List<RecordReferenceCheck> references,
    RecordReferenceRead requiredReference,
    List<RecordReferenceCopyMutation> copies) {
  RecordWritePreparation(
      List<RecordSqlStatement> statements,
      RecordSnapshotPlan snapshot,
      int receiptCount,
      List<RecordReferenceCheck> references,
      RecordReferenceRead requiredReference) {
    this(statements, snapshot, receiptCount, references, requiredReference, List.of());
  }

  RecordWritePreparation(
      List<RecordSqlStatement> statements,
      RecordSnapshotPlan snapshot,
      int receiptCount,
      List<RecordReferenceCheck> references) {
    this(statements, snapshot, receiptCount, references, null);
  }

  RecordWritePreparation(
      final List<RecordSqlStatement> statements, final RecordSnapshotPlan snapshot) {
    this(statements, snapshot, 1);
  }

  RecordWritePreparation(
      final List<RecordSqlStatement> statements,
      final RecordSnapshotPlan snapshot,
      final int receiptCount) {
    this(statements, snapshot, receiptCount, List.of());
  }

  RecordWritePreparation {
    statements = List.copyOf(statements);
    references = List.copyOf(references);
    copies = List.copyOf(copies);
    if (statements.isEmpty()
        || statements.size() > 401
        || receiptCount < 1
        || receiptCount > 100
        || receiptCount > statements.size()) {
      throw new IllegalArgumentException("Prepared record write exceeds its mutation budget");
    }
    if (references.size() > 10_050) {
      throw new IllegalArgumentException("Prepared reference budget exceeded");
    }
    if (copies.size() > 201
        || copies.stream().map(RecordReferenceCopyMutation::key).distinct().count()
            != copies.size()) {
      throw new IllegalArgumentException("Prepared reference origin budget exceeded or duplicated");
    }
    for (var copy : copies) {
      copy.verify(statements);
    }
  }

  /**
   * The reader borrows the same Calcite transaction. Work may be repeated after confirmed rollback;
   * return attempt-local results, retain preallocated IDs and never mutate an external result
   * buffer.
   */
  @FunctionalInterface
  interface Work {
    RecordWritePreparation prepare(
        Function<RecordSelectPlan, CalciteQueryResult> reader,
        Instant at,
        RecordCalculationStore.Installed calculation);
  }
}
