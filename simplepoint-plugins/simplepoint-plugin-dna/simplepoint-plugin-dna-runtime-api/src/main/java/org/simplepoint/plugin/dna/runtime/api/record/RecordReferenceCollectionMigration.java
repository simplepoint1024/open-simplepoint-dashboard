package org.simplepoint.plugin.dna.runtime.api.record;

/** Converts one installed root reference into a distinct collection on the same target model. */
public record RecordReferenceCollectionMigration(
    String sourceRelationId, String destinationRelationId) {
  public RecordReferenceCollectionMigration {
    RecordCalculationDefinition.identity(sourceRelationId);
    RecordCalculationDefinition.identity(destinationRelationId);
    if (sourceRelationId.equals(destinationRelationId)) {
      throw new IllegalArgumentException("A conversion requires a distinct collection identity");
    }
  }
}
