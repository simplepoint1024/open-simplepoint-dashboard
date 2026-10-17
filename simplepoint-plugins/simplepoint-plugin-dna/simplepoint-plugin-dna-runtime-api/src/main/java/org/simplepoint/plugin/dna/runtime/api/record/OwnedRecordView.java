package org.simplepoint.plugin.dna.runtime.api.record;

/**
 * Bounded owned-record contract; table locations and parent references remain server controlled.
 */
public record OwnedRecordView(
    RecordView parent, java.util.Map<String, java.util.List<RecordView>> details) {
  /** Validates and defensively copies the immutable request or snapshot boundary. */
  public OwnedRecordView {
    java.util.Objects.requireNonNull(parent, "parent");
    var copy = new java.util.LinkedHashMap<String, java.util.List<RecordView>>();
    details.forEach((key, value) -> copy.put(key, java.util.List.copyOf(value)));
    details = java.util.Collections.unmodifiableMap(copy);
  }
}
