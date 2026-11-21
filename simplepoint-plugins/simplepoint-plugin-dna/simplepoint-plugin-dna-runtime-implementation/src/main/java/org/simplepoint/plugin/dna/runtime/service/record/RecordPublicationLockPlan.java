package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.List;
import java.util.Objects;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;

/** Bounded transaction-local locks for immutable publication patches, never a durable payload. */
record RecordPublicationLockPlan(
    List<RecordSqlStatement> statements,
    List<RecordReferenceCheck> references,
    Runnable verifyReads) {
  static final int MAX_STATEMENTS = 2_010;
  static final int MAX_REFERENCES = 10_050;
  static final int MAX_CANDIDATES = 10_451;

  RecordPublicationLockPlan {
    statements = List.copyOf(statements);
    references = List.copyOf(references);
    Objects.requireNonNull(verifyReads, "verifyReads");
    if (!fits(statements.size(), references.size())
        || statements.stream().anyMatch(statement -> statement.subject() == null)) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.RECORD_COMMAND_INVALID,
          "Publication patches exceed their complete transaction row-lock budget");
    }
  }

  static boolean fits(int statements, int references) {
    return statements >= 0
        && references >= 0
        && statements <= MAX_STATEMENTS
        && references <= MAX_REFERENCES
        && statements + references <= MAX_CANDIDATES;
  }
}
