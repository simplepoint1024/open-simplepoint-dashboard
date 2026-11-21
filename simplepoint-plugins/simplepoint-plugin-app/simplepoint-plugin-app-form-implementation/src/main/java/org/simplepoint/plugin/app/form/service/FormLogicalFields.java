package org.simplepoint.plugin.app.form.service;

import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;

/** One declared type/modifier translation for physical publication and read-only conversions. */
final class FormLogicalFields {
  private FormLogicalFields() {}

  static LogicalFieldDefinition define(FormFieldDefinition field, LogicalValue defaultValue) {
    return describe(field, field.storageCode(), field.storageLabel(), defaultValue);
  }

  /**
   * An ephemeral conversion type, never a schema contribution. Temporary form values deliberately
   * have no physical names; normalization uses only their declared type, modifiers and nullability.
   */
  static LogicalFieldDefinition valueType(FormFieldDefinition field) {
    return describe(field, "value", field.label(), null);
  }

  private static LogicalFieldDefinition describe(
      FormFieldDefinition field, String code, String label, LogicalValue defaultValue) {
    return new LogicalFieldDefinition(
        field.fieldId(),
        code,
        label,
        LogicalFieldType.valueOf(field.type().name()),
        !field.storageRequired(),
        field.length(),
        field.precision(),
        field.scale(),
        defaultValue);
  }
}
