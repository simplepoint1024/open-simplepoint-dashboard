package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Nullable dynamic value copying shared by mutation commands. */
final class RecordValues {

  private RecordValues() {
  }

  static Map<String, Object> copy(final Map<String, Object> source) {
    if (source == null) {
      return Map.of();
    }
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    source.forEach((field, value) -> {
      if (field == null || !field.matches("[a-z][a-z0-9_]{0,62}")) {
        throw new IllegalArgumentException("Record value field has an invalid format");
      }
      result.put(field, value);
    });
    if (result.size() > 100) {
      throw new IllegalArgumentException("Record mutation exceeds 100 fields");
    }
    return Collections.unmodifiableMap(result);
  }
}
