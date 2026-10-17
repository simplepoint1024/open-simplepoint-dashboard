package org.simplepoint.plugin.dna.runtime.api.record;

/**
 * Bounded owned-record contract; table locations and parent references remain server controlled.
 */
public record OwnedRecordCommand(
    RecordTransactionCommand parent,
    java.util.List<OwnedRecordSet> details,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        OwnedRecordSnapshotRequest snapshot) {
  /** Existing CRUD requests retain their exact fingerprint and do not store business snapshots. */
  public OwnedRecordCommand(
      RecordTransactionCommand parent, java.util.List<OwnedRecordSet> details) {
    this(parent, details, null);
  }

  /** Validates and defensively copies the immutable request or snapshot boundary. */
  public OwnedRecordCommand {
    java.util.Objects.requireNonNull(parent, "parent");
    details = details == null ? java.util.List.of() : java.util.List.copyOf(details);
    if (snapshot != null
        && parent.mutations().stream().anyMatch(RecordDeleteMutation.class::isInstance)) {
      throw new IllegalArgumentException("A deleted aggregate cannot become a submission snapshot");
    }
    if (parent.mutations().size() != 1
        || parent.idempotencyKey() == null
        || details.size() > 5
        || details.stream().mapToInt(set -> set.rows().size()).sum() > 200
        || details.stream().map(set -> set.model().physicalModelId()).distinct().count()
            != details.size()) {
      throw new IllegalArgumentException(
          "Owned record requires one keyed parent and at most 5 details / 200 rows");
    }
  }
}
