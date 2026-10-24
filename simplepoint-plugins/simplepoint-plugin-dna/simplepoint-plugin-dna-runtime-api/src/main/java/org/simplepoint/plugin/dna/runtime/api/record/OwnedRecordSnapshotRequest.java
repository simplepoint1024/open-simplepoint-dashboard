package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.List;

/** Explicit immutable capture of the parent and declared owned collections at commit time. */
public record OwnedRecordSnapshotRequest(List<OwnedRecordModel> details,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    List<RecordSnapshotFieldRequirement> requirements) {
  /** Legacy captures retain their original serialization and idempotency fingerprint. */
  public OwnedRecordSnapshotRequest(final List<OwnedRecordModel> details) {
    this(details, null);
  }

  /** Validates and defensively copies the immutable request or snapshot boundary. */
  public OwnedRecordSnapshotRequest {
    details = details == null ? List.of() : List.copyOf(details);
    if (details.size() > 5
        || details.stream().map(OwnedRecordModel::physicalModelId).distinct().count()
            != details.size()) {
      throw new IllegalArgumentException("Snapshot requires at most five distinct child models");
    }
    if (requirements != null) {
      requirements = List.copyOf(requirements);
      if (requirements.size() > 100 || requirements.stream().map(RecordSnapshotFieldRequirement::fieldId).distinct().count()
          != requirements.size()) {
        throw new IllegalArgumentException("Snapshot requires at most one requirement per field and at most 100 fields");
      }
    }
  }
}
