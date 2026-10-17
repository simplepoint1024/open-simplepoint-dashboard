package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;

/** Detached trusted model metadata used by record compilation and execution. */
record RuntimeRecordModel(
    String physicalModelId,
    String appId,
    String formId,
    int activeVersion,
    PhysicalSchemaDefinition activeSchema,
    int requestedVersion,
    PhysicalSchemaDefinition requestedSchema,
    PhysicalSchemaTarget target
) {

  RuntimeRecordModel(
      final String physicalModelId,
      final String appId,
      final String formId,
      final int activeVersion,
      final PhysicalSchemaDefinition activeSchema,
      final PhysicalSchemaTarget target
  ) {
    this(
        physicalModelId,
        appId,
        formId,
        activeVersion,
        activeSchema,
        activeVersion,
        activeSchema,
        target
    );
  }

  /** Returns request-facing logical field codes in the historical declaration order. */
  List<String> requestedFieldCodes() {
    return requestedSchema.fields().stream().map(LogicalFieldDefinition::code).toList();
  }

  /** Resolves a request-facing code against the immutable requested schema. */
  LogicalFieldDefinition requestedField(final String requestedCode) {
    return requestedSchema.fields().stream()
        .filter(field -> field.code().equals(requestedCode))
        .findFirst()
        .orElse(null);
  }

  /** Resolves the current physical field by the stable identity in the requested schema. */
  LogicalFieldDefinition activeField(final String requestedCode) {
    LogicalFieldDefinition requested = requestedField(requestedCode);
    if (requested == null) {
      return null;
    }
    return activeSchema.fields().stream()
        .filter(field -> field.fieldId().equals(requested.fieldId()))
        .findFirst()
        .orElseThrow(() -> new DnaRuntimeException(
            DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE,
            "Historical record field is missing from the active schema"
        ));
  }

  /** Maps one historical field code to its current physical column code. */
  String activeCode(final String requestedCode) {
    LogicalFieldDefinition active = activeField(requestedCode);
    return active == null ? requestedCode : active.code();
  }

  /** Resolves a stable permission-expression field to the current trusted storage column. */
  String permissionCode(final String permissionField) {
    return activeSchema.fields().stream()
        .filter(field -> org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel.field(field.fieldId()).equals(permissionField))
        .map(LogicalFieldDefinition::code).findFirst().orElse(permissionField);
  }
}
