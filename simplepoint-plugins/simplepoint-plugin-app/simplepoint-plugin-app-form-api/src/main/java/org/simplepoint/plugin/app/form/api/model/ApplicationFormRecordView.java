package org.simplepoint.plugin.app.form.api.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Authorized form record projected back to stable field identities. */
public record ApplicationFormRecordView(
    String id,
    long version,
    FormRecordState state,
    Map<String, Object> values,
    Map<String, Object> systemValues,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        Map<String, FormRecordReferenceLabel> systemLabels,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        java.util.List<org.simplepoint.plugin.app.form.api.model.relation.FormReferenceSnapshot>
            referenceSnapshots) {

  /** Compatibility for existing frozen submissions without captured base fields. */
  public ApplicationFormRecordView(
      String id, long version, FormRecordState state, Map<String, Object> values) {
    this(id, version, state, values, Map.of());
  }

  /** Preserves immutable snapshots and callers without presentation metadata. */
  public ApplicationFormRecordView(
      String id,
      long version,
      FormRecordState state,
      Map<String, Object> values,
      Map<String, Object> systemValues) {
    this(id, version, state, values, systemValues, Map.of());
  }

  /** Preserves records without immutable business-reference presentation metadata. */
  public ApplicationFormRecordView(
      String id,
      long version,
      FormRecordState state,
      Map<String, Object> values,
      Map<String, Object> systemValues,
      Map<String, FormRecordReferenceLabel> systemLabels) {
    this(id, version, state, values, systemValues, systemLabels, java.util.List.of());
  }

  /** Defensively copies nullable dynamic values. */
  public ApplicationFormRecordView {
    values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    systemValues =
        systemValues == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(systemValues));
    systemLabels = systemLabels == null ? Map.of() : Map.copyOf(systemLabels);
    referenceSnapshots =
        referenceSnapshots == null
            ? java.util.List.of()
            : java.util.List.copyOf(referenceSnapshots);
    var keys = new java.util.HashSet<java.util.List<String>>();
    if (referenceSnapshots.size() > 10_050
        || referenceSnapshots.stream()
            .anyMatch(
                label ->
                    !keys.add(java.util.List.of(label.relationId(), label.sourceRecordId())))) {
      throw new IllegalArgumentException(
          "Reference snapshot identity or aggregate budget exceeded");
    }
  }
}
