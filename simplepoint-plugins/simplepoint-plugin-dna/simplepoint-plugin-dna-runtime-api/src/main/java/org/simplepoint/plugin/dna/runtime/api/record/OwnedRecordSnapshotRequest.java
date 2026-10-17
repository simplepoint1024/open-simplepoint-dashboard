package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.List;

/** Explicit immutable capture of the parent and declared owned collections at commit time. */
public record OwnedRecordSnapshotRequest(List<OwnedRecordModel> details) {
  /** Validates and defensively copies the immutable request or snapshot boundary. */
  public OwnedRecordSnapshotRequest {
    details = details == null ? List.of() : List.copyOf(details);
    if (details.size() > 5
        || details.stream().map(OwnedRecordModel::physicalModelId).distinct().count()
            != details.size()) {
      throw new IllegalArgumentException("Snapshot requires at most five distinct child models");
    }
  }
}
