package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.UUID;

/** A preview observation identifies data and policy, never copied values or authorization. */
public record RecordReferenceMappingObservation(
    String revision, String targetRecordId, Long targetVersion) {
  /** An explicit empty selection has neither a target identity nor a target version. */
  public RecordReferenceMappingObservation {
    if (revision == null
        || !revision.matches("[a-f0-9]{64}")
        || (targetRecordId == null) != (targetVersion == null)
        || targetVersion != null && targetVersion < 0) {
      throw new IllegalArgumentException("Invalid reference mapping observation");
    }
    if (targetRecordId != null
        && !UUID.fromString(targetRecordId).toString().equals(targetRecordId)) {
      throw new IllegalArgumentException("A canonical reference target identity is required");
    }
  }
}
