package org.simplepoint.plugin.dna.runtime.api.record;

/**
 * Bounded owned-record contract; table locations and parent references remain server controlled.
 */
public record OwnedRecordView(
    RecordView parent,
    java.util.Map<String, java.util.List<RecordView>> details,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        java.util.List<RecordReferenceSnapshot> referenceSnapshots) {

  /** Preserves ordinary record projections and immutable legacy snapshot JSON. */
  public OwnedRecordView(
      RecordView parent, java.util.Map<String, java.util.List<RecordView>> details) {
    this(parent, details, java.util.List.of());
  }

  /** Validates and defensively copies the immutable request or snapshot boundary. */
  public OwnedRecordView {
    java.util.Objects.requireNonNull(parent, "parent");
    var copy = new java.util.LinkedHashMap<String, java.util.List<RecordView>>();
    details.forEach((key, value) -> copy.put(key, java.util.List.copyOf(value)));
    details = java.util.Collections.unmodifiableMap(copy);
    referenceSnapshots =
        referenceSnapshots == null
            ? java.util.List.of()
            : java.util.List.copyOf(referenceSnapshots);
    if (referenceSnapshots.size() > 10_050
        || referenceSnapshots.stream()
                .map(
                    value ->
                        java.util.List.of(
                            value.relationId(), value.sourceModelId(), value.sourceRecordId()))
                .distinct()
                .count()
            != referenceSnapshots.size()) {
      throw new IllegalArgumentException(
          "Reference snapshots exceed the aggregate identity budget");
    }
  }
}
