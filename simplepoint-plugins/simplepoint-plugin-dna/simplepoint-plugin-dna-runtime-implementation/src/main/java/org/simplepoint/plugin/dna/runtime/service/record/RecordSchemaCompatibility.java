package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;

/** Proves that one historical record projection can execute on the active physical table. */
final class RecordSchemaCompatibility {

  private RecordSchemaCompatibility() {}

  /** Rejects changes that would make historical reads or writes ambiguous or unsafe. */
  static void requireCompatible(
      final PhysicalSchemaDefinition requested, final PhysicalSchemaDefinition active) {
    if (!java.util.Objects.equals(requested.owner(), active.owner())) {
      throw incompatible("Record ownership changed");
    }
    Map<String, LogicalFieldDefinition> activeById = byFieldId(active);
    Map<String, LogicalFieldDefinition> requestedById = byFieldId(requested);
    for (LogicalFieldDefinition historical : requested.fields()) {
      LogicalFieldDefinition current = activeById.get(historical.fieldId());
      if (current == null) {
        throw incompatible("Historical field is no longer available: " + historical.fieldId());
      }
      requireCompatibleField(historical, current);
    }
    for (LogicalFieldDefinition current : active.fields()) {
      if (!requestedById.containsKey(current.fieldId())
          && !current.nullable()
          && current.defaultValue() == null) {
        throw incompatible(
            "The active schema adds a required field without a default: " + current.fieldId());
      }
    }
  }

  private static void requireCompatibleField(
      final LogicalFieldDefinition requested, final LogicalFieldDefinition active) {
    if (requested.type() != active.type()) {
      throw incompatible("Historical field type changed: " + requested.fieldId());
    }
    if (requested.type() == LogicalFieldType.STRING && active.length() < requested.length()) {
      throw incompatible("Historical string field was narrowed: " + requested.fieldId());
    }
    if (requested.type() == LogicalFieldType.DECIMAL
        && (active.scale() < requested.scale()
            || active.precision() - active.scale() < requested.precision() - requested.scale())) {
      throw incompatible("Historical decimal field was narrowed: " + requested.fieldId());
    }
    if (requested.nullable() && !active.nullable()) {
      throw incompatible("Historical optional field became required: " + requested.fieldId());
    }
  }

  private static Map<String, LogicalFieldDefinition> byFieldId(
      final PhysicalSchemaDefinition schema) {
    return schema.fields().stream()
        .collect(
            Collectors.toUnmodifiableMap(LogicalFieldDefinition::fieldId, Function.identity()));
  }

  private static DnaRuntimeException incompatible(final String message) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE, message);
  }
}
