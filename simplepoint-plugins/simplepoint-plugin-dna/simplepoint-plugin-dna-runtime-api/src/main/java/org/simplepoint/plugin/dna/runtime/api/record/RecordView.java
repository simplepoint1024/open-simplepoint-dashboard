package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Authorized dynamic record projection; INT64 and DECIMAL values use lossless decimal strings. */
public record RecordView(String id, long version, Map<String, Object> values) {

  /** Defensively copies nullable dynamic values. */
  public RecordView {
    id = RecordQueryLimits.recordId(id);
    if (version < 0) {
      throw new IllegalArgumentException("Record version cannot be negative");
    }
    values = values == null
        ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }
}
