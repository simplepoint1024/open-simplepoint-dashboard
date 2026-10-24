package org.simplepoint.plugin.dna.runtime.api.record;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/** A committed version or aggregate-only reasons for leaving admission unchanged. */
public record RecordWritePolicyPublicationView(
    boolean published,
    long version,
    String publicationFingerprint,
    Map<String, RecordCompletenessView> violations,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Long> valueViolations,
    @JsonInclude(JsonInclude.Include.NON_NULL) RecordWritePolicyProgress progress
) {

  /** Nonterminal work carries progress; a durable final rejection carries its sequence and counts; committed replays carry neither. */
  public RecordWritePolicyPublicationView {
    RecordQueryLimits.writePolicyVersion(version);
    violations = Map.copyOf(violations);
    valueViolations = valueViolations == null ? Map.of() : Map.copyOf(valueViolations);
    boolean rejected = !violations.isEmpty() || !valueViolations.isEmpty();
    if (publicationFingerprint == null || !publicationFingerprint.matches("[a-f0-9]{64}")
        || published != (progress == null && violations.isEmpty() && valueViolations.isEmpty())
        || progress != null && (progress.phase() == RecordWritePolicyPhase.RESTORING
            || rejected && (progress.phase() != RecordWritePolicyPhase.APPLYING || progress.completedRecords() != progress.totalRecords()
                || progress.failureCode() != org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode.RECORD_WRITE_POLICY_PRECHECK_FAILED))
        || violations.size() + valueViolations.size() > 100
        || valueViolations.values().stream().anyMatch(count -> count <= 0)
        || violations.keySet().stream().anyMatch(valueViolations::containsKey)
        || violations.values().stream().anyMatch(result -> result.emptyCounts().values().stream().noneMatch(count -> count > 0))) {
      throw new IllegalArgumentException("Invalid policy publication result");
    }
  }

  /** Keeps previously persisted inspection responses byte-compatible when no progress is present. */
  public RecordWritePolicyPublicationView(final boolean published, final long version, final String publicationFingerprint,
      final Map<String, RecordCompletenessView> violations, final Map<String, Long> valueViolations) {
    this(published, version, publicationFingerprint, violations, valueViolations, null);
  }

  /** Retains completeness-only result compatibility. */
  public RecordWritePolicyPublicationView(final boolean published, final long version, final String publicationFingerprint,
      final Map<String, RecordCompletenessView> violations) {
    this(published, version, publicationFingerprint, violations, Map.of());
  }
}
