package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.List;
import java.util.Objects;

/** Tenant and scope constrained lookup for one record identity. */
public record RecordLookupQuery(
    String physicalModelId,
    RecordSchemaReference schemaReference,
    String recordId,
    List<String> fields
) {

  /** Validates identities and the requested projection. */
  public RecordLookupQuery {
    physicalModelId = RecordQueryLimits.modelId(physicalModelId);
    Objects.requireNonNull(schemaReference, "schemaReference");
    recordId = RecordQueryLimits.recordId(recordId);
    fields = RecordQueryLimits.fields(fields);
  }
}
