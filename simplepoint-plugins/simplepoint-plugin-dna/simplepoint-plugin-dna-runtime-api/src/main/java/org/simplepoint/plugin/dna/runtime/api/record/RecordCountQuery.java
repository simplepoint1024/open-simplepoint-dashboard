package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.Objects;

/** Structured count query using the same filter and authorization rules as paging. */
public record RecordCountQuery(
    String physicalModelId,
    RecordSchemaReference schemaReference,
    RecordFilter filter
) {

  /** Enforces model identity and filter complexity. */
  public RecordCountQuery {
    physicalModelId = RecordQueryLimits.modelId(physicalModelId);
    Objects.requireNonNull(schemaReference, "schemaReference");
    filter = RecordQueryLimits.filter(filter);
  }
}
