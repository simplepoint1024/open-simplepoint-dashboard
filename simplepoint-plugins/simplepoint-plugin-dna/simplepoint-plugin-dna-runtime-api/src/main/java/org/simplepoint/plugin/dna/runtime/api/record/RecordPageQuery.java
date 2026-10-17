package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.List;
import java.util.Objects;

/** Structured server-side page query for one physical model. */
public record RecordPageQuery(
    String physicalModelId,
    RecordSchemaReference schemaReference,
    int page,
    int size,
    List<String> fields,
    RecordFilter filter,
    List<RecordSort> sorts
) {

  /** Enforces page, projection, filter, and sort budgets before execution. */
  public RecordPageQuery {
    physicalModelId = RecordQueryLimits.modelId(physicalModelId);
    Objects.requireNonNull(schemaReference, "schemaReference");
    if (page < 0 || size < 1 || size > RecordQueryLimits.MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("Record page range is invalid");
    }
    fields = RecordQueryLimits.fields(fields);
    filter = RecordQueryLimits.filter(filter);
    sorts = RecordQueryLimits.sorts(sorts);
  }
}
