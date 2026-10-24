package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.Objects;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;

/** Storage contract for a trusted definition, distinct from an ordinary record command. */
record RecordCalculationPolicy(
    int contractVersion,
    String appId,
    String formId,
    RecordSchemaReference parentSchema,
    RecordCalculationDefinition definition) {

  RecordCalculationPolicy {
    if (contractVersion != 1 || !identity(appId) || !identity(formId)) {
      throw new IllegalArgumentException("Invalid stored calculation policy identity or version");
    }
    Objects.requireNonNull(parentSchema, "parentSchema");
    Objects.requireNonNull(definition, "definition");
  }

  private static boolean identity(final String value) {
    return value != null && !value.isBlank() && value.length() <= 64 && value.equals(value.trim());
  }
}
