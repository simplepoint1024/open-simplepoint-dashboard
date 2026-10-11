package org.simplepoint.plugin.dna.runtime.api.schema;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;

/** Versioned target schema submitted by a form engine. */
public record PhysicalSchemaDefinition(
    String contractVersion,
    List<LogicalFieldDefinition> fields,
    List<LogicalIndexDefinition> indexes
) {

  private static final Set<String> SYSTEM_FIELDS = Set.of(
      "id",
      "tenant_id",
      "record_version",
      "created_by",
      "created_org_dept_id",
      "created_at",
      "updated_by",
      "updated_at",
      "deleted_at"
  );

  /** Validates contract version, stable field identity, and index references. */
  public PhysicalSchemaDefinition {
    if (!DnaRuntimeApiVersion.CURRENT.equals(contractVersion)) {
      throw new IllegalArgumentException("Unsupported schema contract version: " + contractVersion);
    }
    fields = fields == null ? List.of() : List.copyOf(fields);
    indexes = indexes == null ? List.of() : List.copyOf(indexes);
    validateFields(fields);
    validateIndexes(fields, indexes);
  }

  private static void validateFields(final List<LogicalFieldDefinition> fields) {
    Set<String> ids = new HashSet<>();
    Set<String> codes = new HashSet<>();
    for (LogicalFieldDefinition field : fields) {
      if (field == null) {
        throw new IllegalArgumentException("Schema fields cannot contain null entries");
      }
      if (!ids.add(field.fieldId())) {
        throw new IllegalArgumentException("Duplicate fieldId: " + field.fieldId());
      }
      if (!codes.add(field.code())) {
        throw new IllegalArgumentException("Duplicate field code: " + field.code());
      }
      if (SYSTEM_FIELDS.contains(field.code())) {
        throw new IllegalArgumentException("System field is managed by DNA: " + field.code());
      }
    }
  }

  private static void validateIndexes(
      final List<LogicalFieldDefinition> fields,
      final List<LogicalIndexDefinition> indexes
  ) {
    Set<String> fieldCodes = new HashSet<>();
    fields.forEach(field -> fieldCodes.add(field.code()));
    Set<String> indexCodes = new HashSet<>();
    for (LogicalIndexDefinition index : indexes) {
      if (index == null) {
        throw new IllegalArgumentException("Schema indexes cannot contain null entries");
      }
      if (!indexCodes.add(index.code())) {
        throw new IllegalArgumentException("Duplicate index code: " + index.code());
      }
      if (!fieldCodes.containsAll(index.fieldCodes())) {
        throw new IllegalArgumentException("Index references an unknown field: " + index.code());
      }
    }
  }
}
