package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.Objects;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation;

/** Transaction-local state machine; retained versions prevent a delayed create reviving an edge. */
record RecordCollectionEdgeState(long version, boolean active) {
  RecordCollectionEdgeState {
    if (version < -1 || version == Long.MAX_VALUE || version == -1 && active) {
      throw conflict();
    }
  }

  static RecordCollectionEdgeState absent() {
    return new RecordCollectionEdgeState(-1, false);
  }

  static RecordCollectionEdgeState stored(
      RecordCollectionEdgeKey key,
      String id,
      String tenant,
      long version,
      String source,
      String target) {
    if (!key.id().equals(id)
        || !key.tenantId().equals(tenant)
        || version < 0
        || (source == null) != (target == null)
        || source != null && (!key.sourceId().equals(source) || !key.targetId().equals(target))) {
      throw conflict();
    }
    return new RecordCollectionEdgeState(version, source != null);
  }

  RecordCollectionEdgeState transition(long expectedVersion, CollectionEdgeMutation.Mode mode) {
    Objects.requireNonNull(mode, "mode");
    if (expectedVersion != version) {
      throw conflict();
    }
    boolean desired = mode == CollectionEdgeMutation.Mode.LINK;
    return desired == active ? this : new RecordCollectionEdgeState(version + 1, desired);
  }

  private static DnaRuntimeException conflict() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT,
        "The collection edge changed or its stored identity is invalid");
  }
}
