package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.List;

/** Exact batch delete intent for parents and their published detail models. */
public record OwnedRecordDeleteTransaction(RecordTransactionCommand parent, List<OwnedRecordModel> details) {
  public OwnedRecordDeleteTransaction {
    details = details == null ? List.of() : List.copyOf(details);
    if (parent == null || parent.idempotencyKey() == null
        || !parent.mutations().stream().allMatch(RecordDeleteMutation.class::isInstance)
        || parent.mutations().stream().map(mutation -> ((RecordDeleteMutation) mutation).recordId())
            .distinct().count() != parent.mutations().size()
        || details.isEmpty() || details.size() > 5
        || details.stream().map(OwnedRecordModel::physicalModelId).distinct().count() != details.size()) {
      throw new IllegalArgumentException("Batch delete requires distinct keyed parents and owned detail models");
    }
  }
}
