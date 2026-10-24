package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** New rows use create admission; existing rows use the aggregate's prior lifecycle, never submitted state. */
public record RecordFieldWriteRule(RecordValidationField field, boolean create, List<String> updateStates) {
  /** Canonical state sets preserve reproducible publication fingerprints. */
  public RecordFieldWriteRule {
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(updateStates, "updateStates");
    if (updateStates.size() > 32 || new HashSet<>(updateStates).size() != updateStates.size()
        || updateStates.stream().anyMatch(state -> state == null || !state.matches("[A-Za-z][A-Za-z0-9_-]{0,31}"))) {
      throw new IllegalArgumentException("Invalid field write state set");
    }
    updateStates = updateStates.stream().sorted().toList();
  }

  long textBudget() {
    return field.scope().length() + field.fieldId().length() + updateStates.stream().mapToLong(String::length).sum();
  }
}
