package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Authorized prospective values, never a committed record or receipt. Detail positions follow
 * the requested collection (or stored order when omitted); generated row IDs are not exposed.
 * Exact numeric values use the same lossless strings as record reads.
 */
public record OwnedRecordPreview(
    Map<String, Object> values,
    Map<String, List<Map<String, Object>>> details,
    long writePolicyVersion) {

  /** Copies nullable values and collections without manufacturing a persisted identity. */
  public OwnedRecordPreview {
    RecordQueryLimits.writePolicyVersion(writePolicyVersion);
    values = copy(values);
    Map<String, List<Map<String, Object>>> collections = new LinkedHashMap<>();
    details.forEach((model, rows) -> collections.put(model, rows.stream().map(OwnedRecordPreview::copy).toList()));
    details = Collections.unmodifiableMap(collections);
  }

  private static Map<String, Object> copy(final Map<String, Object> values) {
    return Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }
}
