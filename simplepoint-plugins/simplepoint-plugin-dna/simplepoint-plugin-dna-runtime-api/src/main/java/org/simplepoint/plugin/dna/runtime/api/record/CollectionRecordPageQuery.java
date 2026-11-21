package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.Objects;

/** Pages one direction of an installed collection from a fixed, authorized endpoint. */
public record CollectionRecordPageQuery(
    String sourceModelId,
    RecordSchemaReference sourceSchema,
    String relationId,
    Direction direction,
    String anchorRecordId,
    RecordPageQuery records,
    boolean candidates) {
  public enum Direction {
    FROM_SOURCE,
    FROM_TARGET
  }

  public CollectionRecordPageQuery {
    sourceModelId = RecordQueryLimits.modelId(sourceModelId);
    Objects.requireNonNull(sourceSchema, "sourceSchema");
    RecordCalculationDefinition.identity(relationId);
    Objects.requireNonNull(direction, "direction");
    anchorRecordId = CollectionEdgeMutation.canonicalRecordId(anchorRecordId);
    Objects.requireNonNull(records, "records");
  }
}
