package org.simplepoint.plugin.app.form.api.model.relation;

import java.util.UUID;

/** Exact observed mapping revision and target version; neither is an authorization token. */
public record FormReferenceMappingObservation(
    String revision, String targetRecordId, Long targetVersion) {
  /** Explicit clearing retains a policy revision and has no target identity or version. */
  public FormReferenceMappingObservation {
    if (revision == null
        || !revision.matches("[a-f0-9]{64}")
        || (targetRecordId == null) != (targetVersion == null)
        || targetVersion != null && targetVersion < 0
        || targetRecordId != null
            && !UUID.fromString(targetRecordId).toString().equals(targetRecordId)) {
      throw new IllegalArgumentException("Invalid reference mapping observation");
    }
  }
}
