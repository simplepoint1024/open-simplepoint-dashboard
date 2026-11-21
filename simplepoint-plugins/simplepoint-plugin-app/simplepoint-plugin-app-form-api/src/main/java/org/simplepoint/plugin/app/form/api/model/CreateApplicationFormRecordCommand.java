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
    Map<String, Object> values,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingIntent
            referenceMappings) {

  /**
   * Keeps legacy request and immutable submission serialization unchanged when mappings are absent.
   */
  public CreateApplicationFormRecordCommand(
      ApplicationFormRuntimeIdentity runtime,
      String idempotencyKey,
      FormRecordState state,
      Map<String, Object> values) {
    this(runtime, idempotencyKey, state, values, null);
  }

  /** Keeps the dynamic value envelope immutable while preserving nullable values. */
  public CreateApplicationFormRecordCommand {
    Objects.requireNonNull(state, "state");
    values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }
}
