package org.simplepoint.plugin.dna.runtime.service.record;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceCopyOrigin;

/** Internal origin capture; no target identities are added to the public business record view. */
record RecordReferenceCopySnapshot(
    @JsonProperty(required = true) String detailModelId,
    @JsonProperty(required = true) String sourceRecordId,
    @JsonProperty(required = true) long sourceVersion,
    @JsonProperty(required = true) List<RecordReferenceCopyOrigin> origins,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        List<RecordDerivedCopyOrigin> derivedOrigins) {
  RecordReferenceCopySnapshot(
      String detailModelId,
      String sourceRecordId,
      long sourceVersion,
      List<RecordReferenceCopyOrigin> origins) {
    this(detailModelId, sourceRecordId, sourceVersion, origins, List.of());
  }

  RecordReferenceCopySnapshot {
    new RecordReferenceCopyStore.Key(
        "snapshot", detailModelId == null ? "parent" : detailModelId, sourceRecordId);
    var checked =
        new RecordReferenceCopyStore.Stored(
            sourceVersion, origins, derivedOrigins == null ? List.of() : derivedOrigins);
    origins = checked.origins();
    derivedOrigins = checked.derivedOrigins();
    if (origins.isEmpty() && derivedOrigins.isEmpty()) {
      throw new IllegalArgumentException(
          "An origin snapshot must capture at least one copied field");
    }
  }
}
