package org.simplepoint.plugin.dna.runtime.api.schema;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;

/**
 * Versioned target schema submitted by a form engine. The optional tenantRecordIdentity flag opts
 * into a platform-owned unique (tenant_id, id) key; ordinary evolution cannot remove it once
 * enabled.
 */
public record PhysicalSchemaDefinition(
    String contractVersion,
    List<LogicalFieldDefinition> fields,
    List<LogicalIndexDefinition> indexes,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        OwnedModelRelation owner,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        Boolean tenantRecordIdentity,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        List<LogicalReferenceDefinition> references,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        CollectionModelRelation collection) {

  private static final Set<String> SYSTEM_FIELDS =
      Set.of(
          "id",
          "tenant_id",
          "record_version",
          "created_by",
          "created_org_dept_id",
          "created_at",
          "updated_by",
          "updated_at",
          "deleted_at");

  /** Preserves compatibility for callers without optional snapshot or scene metadata. */
  public PhysicalSchemaDefinition(
      String contractVersion,
      List<LogicalFieldDefinition> fields,
      List<LogicalIndexDefinition> indexes) {
    this(contractVersion, fields, indexes, null);
  }

  /**
   * Keeps legacy owned and independent schema snapshots unchanged when the identity key is absent.
   */
  public PhysicalSchemaDefinition(
      String contractVersion,
      List<LogicalFieldDefinition> fields,
      List<LogicalIndexDefinition> indexes,
      OwnedModelRelation owner) {
    this(contractVersion, fields, indexes, owner, null);
  }

  /** Preserves historical snapshots without business references. */
  public PhysicalSchemaDefinition(
      String contractVersion,
      List<LogicalFieldDefinition> fields,
      List<LogicalIndexDefinition> indexes,
      OwnedModelRelation owner,
      Boolean tenantRecordIdentity) {
    this(contractVersion, fields, indexes, owner, tenantRecordIdentity, List.of());
  }

  /** Validates contract version, stable field identity, and index references. */
  public PhysicalSchemaDefinition(
      String contractVersion,
      List<LogicalFieldDefinition> fields,
      List<LogicalIndexDefinition> indexes,
      OwnedModelRelation owner,
      Boolean tenantRecordIdentity,
      List<LogicalReferenceDefinition> references) {
    this(contractVersion, fields, indexes, owner, tenantRecordIdentity, references, null);
  }

  /** Validates contract version, stable field identity, and index references. */
  public PhysicalSchemaDefinition {
    if (!DnaRuntimeApiVersion.CURRENT.equals(contractVersion)) {
      throw new IllegalArgumentException("Unsupported schema contract version: " + contractVersion);
    }
    fields = fields == null ? List.of() : List.copyOf(fields);
    indexes = indexes == null ? List.of() : List.copyOf(indexes);
    references =
        references == null
            ? List.of()
            : references.stream()
                .sorted(java.util.Comparator.comparing(LogicalReferenceDefinition::relationId))
                .toList();
    if (references.size() > 50) {
      throw new IllegalArgumentException("A model supports at most 50 references");
    }
    validateFields(fields);
    validateIndexes(fields, indexes);
    Set<String> relationIds = new HashSet<>();
    Set<String> referenceFields = new HashSet<>();
    for (var reference : references) {
      if (!relationIds.add(reference.relationId()) || !referenceFields.add(reference.fieldId())) {
        throw new IllegalArgumentException(
            "References must have distinct relation and source field identities");
      }
      var field =
          fields.stream()
              .filter(value -> value.fieldId().equals(reference.fieldId()))
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException("Reference source field is unknown"));
      if (field.type() != LogicalFieldType.UUID
          || field.defaultValue() != null && field.defaultValue().kind() != LogicalValueKind.NULL) {
        throw new IllegalArgumentException(
            "Reference source must be UUID without a fixed target default");
      }
      if (owner != null && owner.parentFieldId().equals(reference.fieldId())) {
        throw new IllegalArgumentException(
            "Owned parent link cannot also be an independent business reference");
      }
    }
    if (owner != null
        && fields.stream()
            .noneMatch(
                field ->
                    field.fieldId().equals(owner.parentFieldId())
                        && field.type() == LogicalFieldType.UUID
                        && !field.nullable())) {
      throw new IllegalArgumentException("Owned model requires a non-null UUID parent reference");
    }
    if (collection != null) {
      collection.validate(fields, indexes, owner, tenantRecordIdentity, references);
    }
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
      final List<LogicalFieldDefinition> fields, final List<LogicalIndexDefinition> indexes) {
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
