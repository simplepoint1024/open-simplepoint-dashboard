package org.simplepoint.plugin.dna.runtime.api.record;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/** A committed version or aggregate-only reasons for leaving admission unchanged. */
public record RecordWritePolicyPublicationView(
    boolean published,
    long version,
    String publicationFingerprint,
    Map<String, RecordCompletenessView> violations,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Long> valueViolations
) {

  /** An unsuccessful inspection contains violations; a committed replay does not repeat old scans. */
  public RecordWritePolicyPublicationView {
    RecordQueryLimits.writePolicyVersion(version);
    violations = Map.copyOf(violations);
    valueViolations = valueViolations == null ? Map.of() : Map.copyOf(valueViolations);
    if (publicationFingerprint == null || !publicationFingerprint.matches("[a-f0-9]{64}")
        || published != (violations.isEmpty() && valueViolations.isEmpty()) || violations.size() + valueViolations.size() > 100
        || valueViolations.values().stream().anyMatch(count -> count <= 0)
        || violations.keySet().stream().anyMatch(valueViolations::containsKey)
        || violations.values().stream().anyMatch(result -> result.emptyCounts().values().stream().noneMatch(count -> count > 0))) {
      throw new IllegalArgumentException("Invalid policy publication result");
    }
  }

  /** Retains completeness-only result compatibility. */
  public RecordWritePolicyPublicationView(final boolean published, final long version, final String publicationFingerprint,
      final Map<String, RecordCompletenessView> violations) {
    this(published, version, publicationFingerprint, violations, Map.of());
  }
}
