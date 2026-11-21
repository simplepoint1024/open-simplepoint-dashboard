package org.simplepoint.plugin.dna.runtime.api.record;

/**
 * Immutable relation label captured beside the owning aggregate, never supplied by business values.
 */
public record RecordReferenceSnapshot(
    String relationId,
    String sourceModelId,
    String sourceRecordId,
    String sourceFieldId,
    String targetModelId,
    String targetAppId,
    String targetFormId,
    String targetResourceCode,
    String targetRecordId,
    long targetVersion,
    String labelFieldId,
    String label) {

  /** Technical allocation bound; the complete serialized aggregate has a smaller shared budget. */
  public static final int MAX_LABEL_LENGTH = 1_048_576;

  /** Validates bounded provenance; later reads must independently authorize both ends. */
  public RecordReferenceSnapshot {
    RecordCalculationDefinition.identity(relationId);
    RecordCalculationDefinition.identity(sourceFieldId);
    RecordCalculationDefinition.identity(labelFieldId);
    sourceModelId = RecordQueryLimits.modelId(sourceModelId);
    sourceRecordId = RecordQueryLimits.recordId(sourceRecordId);
    targetModelId = RecordQueryLimits.modelId(targetModelId);
    targetAppId = RecordQueryLimits.modelId(targetAppId);
    targetFormId = RecordQueryLimits.modelId(targetFormId);
    targetRecordId = RecordQueryLimits.recordId(targetRecordId);
    if (targetVersion < 0
        || targetResourceCode == null
        || targetResourceCode.isBlank()
        || targetResourceCode.length() > 200
        || !targetResourceCode.equals(targetResourceCode.trim())
        || label != null && label.length() > MAX_LABEL_LENGTH) {
      throw new IllegalArgumentException("Invalid reference snapshot provenance or label budget");
    }
  }
}
