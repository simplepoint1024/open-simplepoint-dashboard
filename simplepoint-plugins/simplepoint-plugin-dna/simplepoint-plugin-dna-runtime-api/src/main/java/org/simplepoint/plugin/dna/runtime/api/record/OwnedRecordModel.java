package org.simplepoint.plugin.dna.runtime.api.record;

/**
 * Bounded owned-record contract; table locations and parent references remain server controlled.
 */
public record OwnedRecordModel(String physicalModelId, RecordSchemaReference schemaReference) {
  /** Validates and defensively copies the immutable request or snapshot boundary. */
  public OwnedRecordModel {
    physicalModelId = RecordQueryLimits.modelId(physicalModelId);
    java.util.Objects.requireNonNull(schemaReference, "schemaReference");
  }
}
