package org.simplepoint.plugin.app.form.api.model.relation;

import java.util.Map;
import java.util.Objects;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;

/** Canonical values by stable destination field ID, separated from editable suggestions. */
public record FormReferenceMappingView(
    FormReferenceMappingObservation observation,
    Map<String, FormValue> snapshots,
    Map<String, FormValue> defaults) {
  /** Preserves numeric precision and Unicode without exposing physical column names. */
  public FormReferenceMappingView {
    Objects.requireNonNull(observation, "observation");
    snapshots = Map.copyOf(snapshots);
    defaults = Map.copyOf(defaults);
    if (snapshots.size() + defaults.size() > 32
        || snapshots.keySet().stream().anyMatch(defaults::containsKey)
        || observation.targetRecordId() == null
            && java.util.stream.Stream.concat(
                    snapshots.values().stream(), defaults.values().stream())
                .anyMatch(value -> value.kind() != FormValueKind.NULL)) {
      throw new IllegalArgumentException("Invalid reference mapping output");
    }
    snapshots.keySet().forEach(FormRelationIdentity::require);
    defaults.keySet().forEach(FormRelationIdentity::require);
  }
}
