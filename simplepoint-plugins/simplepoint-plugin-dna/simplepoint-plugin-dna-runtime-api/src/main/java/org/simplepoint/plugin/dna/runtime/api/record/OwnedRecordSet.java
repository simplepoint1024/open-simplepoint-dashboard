package org.simplepoint.plugin.dna.runtime.api.record;

/**
 * Bounded owned-record contract; table locations and parent references remain server controlled.
 */
public record OwnedRecordSet(OwnedRecordModel model, java.util.List<OwnedRecordRow> rows) {
  /** Validates and defensively copies the immutable request or snapshot boundary. */
  public OwnedRecordSet {
    java.util.Objects.requireNonNull(model, "model");
    rows = rows == null ? java.util.List.of() : java.util.List.copyOf(rows);
    if (rows.size() > 40) {
      throw new IllegalArgumentException("Detail row budget exceeded");
    }
    var ids = rows.stream().map(OwnedRecordRow::id).filter(java.util.Objects::nonNull).toList();
    if (new java.util.HashSet<>(ids).size() != ids.size()) {
      throw new IllegalArgumentException("Duplicate detail row identity");
    }
  }
}
