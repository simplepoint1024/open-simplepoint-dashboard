package org.simplepoint.plugin.app.form.api.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only prospective create/update; formula and physical model identities are server-owned. */
public record PreviewApplicationFormRecordCommand(
    ApplicationFormRuntimeIdentity runtime,
    String recordId,
    Long expectedVersion,
    FormRecordState state,
    Map<String, Object> values) {

  /** Requires an exact optimistic identity for existing records and bounds the input envelope. */
  public PreviewApplicationFormRecordCommand {
    if (runtime == null || state == null || (recordId == null) != (expectedVersion == null)
        || recordId != null && (recordId.isBlank() || recordId.length() > 64 || expectedVersion < 0)) {
      throw new IllegalArgumentException("Invalid form record preview intent");
    }
    values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    if (values.size() > 305) {
      throw new IllegalArgumentException("Form record preview budget exceeded");
    }
  }
}
