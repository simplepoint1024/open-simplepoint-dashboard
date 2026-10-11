package org.simplepoint.plugin.dna.runtime.api.record;

/** Structured count query using the same filter and authorization rules as paging. */
public record RecordCountQuery(String physicalModelId, RecordFilter filter) {

  /** Enforces model identity and filter complexity. */
  public RecordCountQuery {
    physicalModelId = RecordQueryLimits.modelId(physicalModelId);
    filter = RecordQueryLimits.filter(filter);
  }
}
