package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.Objects;

/** Owner-scoped count of records violating a typed invariant; no business values or identities are returned. */
public record RecordViolationQuery(
    String physicalModelId,
    String appId,
    String formId,
    RecordSchemaReference schemaReference,
    RecordFilter filter,
    RecordValueCondition condition,
    RecordJsonSemantics jsonSemantics
) {

  /** A violation predicate is explicit and bounded; a missing predicate must never silently accept records. */
  public RecordViolationQuery {
    physicalModelId = RecordQueryLimits.modelId(physicalModelId);
    appId = RecordQueryLimits.modelId(appId);
    formId = RecordQueryLimits.modelId(formId);
    Objects.requireNonNull(schemaReference, "schemaReference");
    filter = RecordQueryLimits.filter(filter);
    condition = RecordValueCondition.bounded(Objects.requireNonNull(condition, "condition"));
    Objects.requireNonNull(jsonSemantics, "jsonSemantics");
  }
}
