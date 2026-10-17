package org.simplepoint.plugin.app.form.api.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Browser-facing create intent using stable form field identities. */
public record CreateApplicationFormRecordCommand(
    ApplicationFormRuntimeIdentity runtime,
    String idempotencyKey,
    FormRecordState state,
    Map<String, Object> values
) {

  /** Keeps the dynamic value envelope immutable while preserving nullable values. */
  public CreateApplicationFormRecordCommand {
    Objects.requireNonNull(state, "state");
    values = values == null
        ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }
}
