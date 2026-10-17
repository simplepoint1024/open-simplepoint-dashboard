package org.simplepoint.plugin.app.form.service;

import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCompletenessQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordViolationQuery;

/** Server-compiled checks tied to the exact policy and active-page set observed during impact analysis. */
public record FormWritePolicyImpact(long expectedVersion, int governingVersion,
    Set<String> activeBindingIds, Map<String, RecordCompletenessQuery> checks, Map<String, RecordViolationQuery> valueChecks) {

  /** Keeps the observation immutable so a later publication cannot silently reuse stale analysis. */
  public FormWritePolicyImpact {
    activeBindingIds = Set.copyOf(activeBindingIds);
    checks = Map.copyOf(checks);
    valueChecks = Map.copyOf(valueChecks);
    if (expectedVersion < 0 || governingVersion < 1 || activeBindingIds.size() > 1000
        || activeBindingIds.stream().anyMatch(id -> id.isBlank() || id.length() > 36)) {
      throw new IllegalArgumentException("Invalid form policy impact observation");
    }
  }

  /** Retains the completeness-only observation contract. */
  public FormWritePolicyImpact(final long expectedVersion, final int governingVersion,
      final Set<String> activeBindingIds, final Map<String, RecordCompletenessQuery> checks) {
    this(expectedVersion, governingVersion, activeBindingIds, checks, Map.of());
  }
}
