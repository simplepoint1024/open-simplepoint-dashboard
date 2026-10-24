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
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    Map<String, FormRecordReferenceLabel> systemLabels
) {

  /** Compatibility for existing frozen submissions without captured base fields. */
  public ApplicationFormRecordView(String id, long version, FormRecordState state, Map<String, Object> values) {
    this(id, version, state, values, Map.of());
  }

  /** Preserves immutable snapshots and callers without presentation metadata. */
  public ApplicationFormRecordView(String id, long version, FormRecordState state,
      Map<String, Object> values, Map<String, Object> systemValues) {
    this(id, version, state, values, systemValues, Map.of());
  }

  /** Defensively copies nullable dynamic values. */
  public ApplicationFormRecordView {
    values = values == null
        ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    systemValues = systemValues == null
        ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(systemValues));
    systemLabels = systemLabels == null ? Map.of() : Map.copyOf(systemLabels);
  }
}
