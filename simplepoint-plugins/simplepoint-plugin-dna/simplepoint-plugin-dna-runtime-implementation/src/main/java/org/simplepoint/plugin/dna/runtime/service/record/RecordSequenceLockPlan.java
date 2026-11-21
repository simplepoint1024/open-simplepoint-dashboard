package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutation;

/** Transaction-local discovery; no user-supplied SQL, authorization snapshot or business effect. */
record RecordSequenceLockPlan(
    List<Source> sources, List<Reference> references, Runnable verifyReads) {
  RecordSequenceLockPlan {
    sources = List.copyOf(sources);
    references = List.copyOf(references);
    Objects.requireNonNull(verifyReads, "verifyReads");
    if (sources.isEmpty() || sources.size() > 10_451 || references.size() > 10_050) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.RECORD_COMMAND_INVALID,
          "Record sequence exceeds its transaction-wide row-lock budget");
    }
  }

  record Source(String modelId, String recordId) {}

  record Reference(String sourceModelId, String relationId, String targetRecordId) {}

  record Intent(RecordMutation mutation, String recordId) {}

  @FunctionalInterface
  interface Discovery {
    RecordSequenceLockPlan discover(
        Function<RecordSelectPlan, CalciteQueryResult> reader,
        RecordCalculationStore.Installed installed);
  }
}
