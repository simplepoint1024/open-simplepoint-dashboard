package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.List;
import java.util.Objects;

/** Reads the current versions of a bounded set of exact endpoint pairs before editing. */
public record CollectionRecordObservationQuery(
    String sourceModelId,
    RecordSchemaReference sourceSchema,
    String relationId,
    String sourceRecordId,
    List<String> targetRecordIds) {
  public CollectionRecordObservationQuery {
    sourceModelId = RecordQueryLimits.modelId(sourceModelId);
    Objects.requireNonNull(sourceSchema, "sourceSchema");
    RecordCalculationDefinition.identity(relationId);
    sourceRecordId = CollectionEdgeMutation.canonicalRecordId(sourceRecordId);
    if (targetRecordIds == null || targetRecordIds.isEmpty() || targetRecordIds.size() > 100) {
      throw new IllegalArgumentException("Expected 1 to 100 distinct collection targets");
    }
    targetRecordIds =
        targetRecordIds.stream().map(CollectionEdgeMutation::canonicalRecordId).toList();
    if (targetRecordIds.stream().distinct().count() != targetRecordIds.size()) {
      throw new IllegalArgumentException("Expected 1 to 100 distinct collection targets");
    }
  }
}
