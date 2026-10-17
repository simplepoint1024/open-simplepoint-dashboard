package org.simplepoint.plugin.dna.runtime.api.record;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;

/**
 * Bounded owner-management inspection of missing values, independent of a reader's row scope.
 * This is a data snapshot, not an admission fence or a guarantee about subsequent writes.
 */
public record RecordCompletenessQuery(
    String physicalModelId,
    String appId,
    String formId,
    RecordSchemaReference schemaReference,
    List<String> fields,
    RecordFilter filter,
    @JsonInclude(JsonInclude.Include.NON_NULL) RecordValueCondition condition,
    @JsonInclude(JsonInclude.Include.NON_NULL) RecordJsonSemantics jsonSemantics
) {

  /** Requires an explicit owner, immutable schema and between one and 100 unique fields. */
  public RecordCompletenessQuery {
    physicalModelId = RecordQueryLimits.modelId(physicalModelId);
    appId = ownerId(appId, "appId");
    formId = ownerId(formId, "formId");
    Objects.requireNonNull(schemaReference, "schemaReference");
    fields = RecordQueryLimits.fields(fields);
    if (fields.isEmpty()) {
      throw new IllegalArgumentException("At least one completeness field is required");
    }
    filter = RecordQueryLimits.filter(filter);
    condition = RecordValueCondition.bounded(condition);
  }

  /** Retains the original inspection contract and its durable publication fingerprints. */
  public RecordCompletenessQuery(final String physicalModelId, final String appId, final String formId,
      final RecordSchemaReference schemaReference, final List<String> fields, final RecordFilter filter) {
    this(physicalModelId, appId, formId, schemaReference, fields, filter, null, null);
  }

  /** Existing typed inspections retain SQL-null-only JSON emptiness unless explicitly upgraded. */
  public RecordCompletenessQuery(final String physicalModelId, final String appId, final String formId,
      final RecordSchemaReference schemaReference, final List<String> fields, final RecordFilter filter, final RecordValueCondition condition) {
    this(physicalModelId, appId, formId, schemaReference, fields, filter, condition, null);
  }

  private static String ownerId(final String value, final String name) {
    if (value == null || value.isBlank() || value.length() > 64) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value.trim();
  }
}
