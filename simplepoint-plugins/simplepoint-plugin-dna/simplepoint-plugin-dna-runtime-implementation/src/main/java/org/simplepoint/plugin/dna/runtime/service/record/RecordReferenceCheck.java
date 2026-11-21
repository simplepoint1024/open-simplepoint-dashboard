package org.simplepoint.plugin.dna.runtime.service.record;

/**
 * Transaction-local server comparison of one retained or prospective reference, never a wire DTO.
 */
record RecordReferenceCheck(
    String relationId,
    String sourceModelId,
    String sourceRecordId,
    String targetRecordId,
    boolean requireAuthorization,
    Long expectedTargetVersion,
    String mappingRevision) {
  RecordReferenceCheck(
      String relationId,
      String sourceModelId,
      String sourceRecordId,
      String targetRecordId,
      boolean requireAuthorization,
      Long expectedTargetVersion) {
    this(
        relationId,
        sourceModelId,
        sourceRecordId,
        targetRecordId,
        requireAuthorization,
        expectedTargetVersion,
        null);
  }

  RecordReferenceCheck(
      String relationId,
      String sourceModelId,
      String sourceRecordId,
      String targetRecordId,
      boolean requireAuthorization) {
    this(relationId, sourceModelId, sourceRecordId, targetRecordId, requireAuthorization, null);
  }

  RecordReferenceCheck {
    if (expectedTargetVersion != null
        && (expectedTargetVersion < 0 || targetRecordId == null || !requireAuthorization)) {
      throw RecordReferenceAdmission.incompatible();
    }
    if (mappingRevision != null
        && (!mappingRevision.matches("[a-f0-9]{64}")
            || targetRecordId != null && expectedTargetVersion == null)) {
      throw RecordReferenceAdmission.incompatible();
    }
  }
}
