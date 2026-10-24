package org.simplepoint.plugin.dna.runtime.api.record;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;

/** Durable target progress; counts refer to parent aggregates, never individual detail rows. */
public record RecordWritePolicyProgress(
    int contractVersion,
    RecordWritePolicyPhase phase,
    long sequence,
    long completedRecords,
    long totalRecords,
    @JsonInclude(JsonInclude.Include.NON_NULL) DnaRuntimeErrorCode failureCode) {

  /** A completed batch count is not a target activation/cancellation acknowledgement. */
  public RecordWritePolicyProgress {
    Objects.requireNonNull(phase, "phase");
    if (contractVersion != 1 || sequence < 1 || completedRecords < 0 || totalRecords < 0 || completedRecords > totalRecords) {
      throw new IllegalArgumentException("Invalid policy publication progress");
    }
  }
}
