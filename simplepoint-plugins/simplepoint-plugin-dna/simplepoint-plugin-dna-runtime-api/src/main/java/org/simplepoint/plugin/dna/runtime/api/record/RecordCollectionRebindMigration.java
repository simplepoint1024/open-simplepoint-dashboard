package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.Map;

/** Explicit saved-record identities for a new collection on a different target form. */
public record RecordCollectionRebindMigration(
    String sourceRelationId, String destinationRelationId, Map<String, String> targetRecords) {
  private static final int MAX_MAPPINGS = 16_000;

  public RecordCollectionRebindMigration {
    RecordCalculationDefinition.identity(sourceRelationId);
    RecordCalculationDefinition.identity(destinationRelationId);
    if (sourceRelationId.equals(destinationRelationId)
        || targetRecords == null
        || targetRecords.size() > MAX_MAPPINGS) {
      throw new IllegalArgumentException("Invalid collection replacement or target mapping budget");
    }
    targetRecords.forEach(
        (source, target) -> {
          requireRecordId(source);
          requireRecordId(target);
        });
    targetRecords = Map.copyOf(targetRecords);
  }

  private static void requireRecordId(String id) {
    if (id == null || !id.equals(CollectionEdgeMutation.canonicalRecordId(id))) {
      throw new IllegalArgumentException("A canonical saved record identity is required");
    }
  }
}
