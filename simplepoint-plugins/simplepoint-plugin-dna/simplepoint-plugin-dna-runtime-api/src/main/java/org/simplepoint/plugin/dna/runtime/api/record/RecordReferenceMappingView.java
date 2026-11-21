package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.Map;
import java.util.Objects;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;

/** Canonical preview values; original permission dependencies stay within the trusted DNA store. */
public record RecordReferenceMappingView(
    RecordReferenceMappingObservation observation,
    Map<String, LogicalValue> snapshots,
    Map<String, LogicalValue> defaults) {
  /** Keeps server-managed outputs separate from suggestions requiring explicit user acceptance. */
  public RecordReferenceMappingView {
    Objects.requireNonNull(observation, "observation");
    snapshots = Map.copyOf(snapshots);
    defaults = Map.copyOf(defaults);
    if (snapshots.size() + defaults.size() > 32
        || snapshots.keySet().stream().anyMatch(defaults::containsKey)) {
      throw new IllegalArgumentException("Invalid mapping output projection");
    }
    if (observation.targetRecordId() == null
        && java.util.stream.Stream.concat(snapshots.values().stream(), defaults.values().stream())
            .anyMatch(
                value ->
                    value.kind()
                        != org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind.NULL)) {
      throw new IllegalArgumentException("Cleared references cannot supply non-null values");
    }
    snapshots.keySet().forEach(RecordCalculationDefinition::identity);
    defaults.keySet().forEach(RecordCalculationDefinition::identity);
  }
}
