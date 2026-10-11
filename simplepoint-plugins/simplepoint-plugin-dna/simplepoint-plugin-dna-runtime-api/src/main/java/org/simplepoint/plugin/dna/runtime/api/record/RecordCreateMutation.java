package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.Map;

/** Ordered create operation inside a record transaction. */
public record RecordCreateMutation(String reference, Map<String, Object> values)
    implements RecordMutation {

  /** Validates correlation identity and values. */
  public RecordCreateMutation {
    reference = requireReference(reference);
    values = RecordValues.copy(values);
  }

  private static String requireReference(final String value) {
    if (value == null || value.isBlank() || value.length() > 64) {
      throw new IllegalArgumentException("Mutation reference is required");
    }
    return value.trim();
  }
}
