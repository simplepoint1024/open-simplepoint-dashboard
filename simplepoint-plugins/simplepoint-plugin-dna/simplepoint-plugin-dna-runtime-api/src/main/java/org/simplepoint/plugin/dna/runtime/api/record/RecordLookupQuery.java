package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.List;

/** Tenant and scope constrained lookup for one record identity. */
public record RecordLookupQuery(
    String physicalModelId,
    String recordId,
    List<String> fields
) {

  /** Validates identities and the requested projection. */
  public RecordLookupQuery {
    physicalModelId = RecordQueryLimits.modelId(physicalModelId);
    recordId = RecordQueryLimits.recordId(recordId);
    fields = RecordQueryLimits.fields(fields);
  }
}
