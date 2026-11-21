package org.simplepoint.plugin.app.form.api.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Optimistic browser-facing update intent using stable form field identities. */
public record UpdateApplicationFormRecordCommand(
    ApplicationFormRuntimeIdentity runtime,
    long expectedVersion,
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
  public UpdateApplicationFormRecordCommand(
      ApplicationFormRuntimeIdentity runtime,
      long expectedVersion,
      String idempotencyKey,
      FormRecordState state,
      Map<String, Object> values) {
    this(runtime, expectedVersion, idempotencyKey, state, values, null);
  }

  /** Keeps the target state and partial value envelope explicit and immutable. */
  public UpdateApplicationFormRecordCommand {
    if (expectedVersion < 0) {
      throw new IllegalArgumentException("expectedVersion cannot be negative");
    }
    Objects.requireNonNull(state, "state");
    values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }
}
