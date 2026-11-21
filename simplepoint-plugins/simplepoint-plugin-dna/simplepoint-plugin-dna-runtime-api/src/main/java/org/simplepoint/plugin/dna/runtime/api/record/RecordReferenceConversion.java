package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.Set;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;

/**
 * Registered conversion identities for reference values; no executable user-supplied expressions.
 */
public enum RecordReferenceConversion {
  IDENTITY,
  STRING,
  INTEGER,
  DECIMAL;

  /** Validates declared types independently of an individual nullable record value. */
  public boolean supports(LogicalFieldType source, LogicalFieldType destination) {
    if (source == null
        || destination == null
        || Set.of(LogicalFieldType.JSON, LogicalFieldType.BINARY).contains(source)
        || Set.of(LogicalFieldType.JSON, LogicalFieldType.BINARY).contains(destination)) {
      return false;
    }
    boolean numeric =
        Set.of(
                LogicalFieldType.STRING,
                LogicalFieldType.TEXT,
                LogicalFieldType.INT32,
                LogicalFieldType.INT64,
                LogicalFieldType.DECIMAL)
            .contains(source);
    return switch (this) {
      case IDENTITY -> source == destination;
      case STRING -> Set.of(LogicalFieldType.STRING, LogicalFieldType.TEXT).contains(destination);
      case INTEGER ->
          numeric && Set.of(LogicalFieldType.INT32, LogicalFieldType.INT64).contains(destination);
      case DECIMAL -> numeric && destination == LogicalFieldType.DECIMAL;
    };
  }
}
