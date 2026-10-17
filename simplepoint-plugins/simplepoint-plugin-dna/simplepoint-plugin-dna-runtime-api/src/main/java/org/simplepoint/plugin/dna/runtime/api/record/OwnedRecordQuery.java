package org.simplepoint.plugin.dna.runtime.api.record;

/**
 * Bounded owned-record contract; table locations and parent references remain server controlled.
 */
public record OwnedRecordQuery(RecordLookupQuery parent, java.util.List<OwnedRecordModel> details) {
  /** Validates and defensively copies the immutable request or snapshot boundary. */
  public OwnedRecordQuery {
    java.util.Objects.requireNonNull(parent, "parent");
    details = details == null ? java.util.List.of() : java.util.List.copyOf(details);
    if (details.size() > 5
        || details.stream().map(OwnedRecordModel::physicalModelId).distinct().count()
            != details.size()) {
      throw new IllegalArgumentException("Owned record detail model budget exceeded");
    }
  }
}
