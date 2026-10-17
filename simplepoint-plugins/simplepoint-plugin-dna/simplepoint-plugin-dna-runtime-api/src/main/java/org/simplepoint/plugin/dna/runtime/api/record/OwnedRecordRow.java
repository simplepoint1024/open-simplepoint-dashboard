package org.simplepoint.plugin.dna.runtime.api.record;

/**
 * Bounded owned-record contract; table locations and parent references remain server controlled.
 */
public record OwnedRecordRow(
    String id, Long expectedVersion, java.util.Map<String, Object> values) {
  /** Validates and defensively copies the immutable request or snapshot boundary. */
  public OwnedRecordRow {
    if (id != null) {
      id = RecordQueryLimits.recordId(id);
    }
    if ((id == null) != (expectedVersion == null)
        || expectedVersion != null && expectedVersion < 0) {
      throw new IllegalArgumentException("Existing detail rows require an identity and version");
    }
    values = RecordValues.copy(values);
  }
}
