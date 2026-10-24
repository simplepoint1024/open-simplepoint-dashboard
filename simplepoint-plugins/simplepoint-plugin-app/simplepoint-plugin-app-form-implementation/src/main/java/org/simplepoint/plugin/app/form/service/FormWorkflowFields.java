package org.simplepoint.plugin.app.form.service;

import java.util.Set;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;

/** Shared field boundary for workflow discovery, binding validation and committed-value mapping. */
public final class FormWorkflowFields {
  private FormWorkflowFields() {
  }

  /** Attachments keep their own reference contract; transient and legacy computed placeholders have no committed scalar value. */
  public static boolean supports(final FormFieldDefinition field) {
    return field != null && field.persistent() && field.storageMode() != FormStorageMode.ATTACHMENT
        && field.type() != FormFieldType.JSON;
  }

  /** Preserves exact numeric and civil-time contracts when mapping to declared process variable types. */
  public static boolean compatible(final FormFieldType field, final String variable) {
    if (field == null || variable == null) {
      return false;
    }
    return switch (variable) {
      case "decimal" -> Set.of(FormFieldType.INT32, FormFieldType.INT64, FormFieldType.DECIMAL).contains(field);
      case "number" -> field == FormFieldType.INT32;
      case "boolean" -> field == FormFieldType.BOOLEAN;
      case "date" -> field == FormFieldType.DATE;
      case "date-time" -> field == FormFieldType.DATETIME;
      case "user", "role" -> field == FormFieldType.STRING || field == FormFieldType.UUID;
      case "string" -> Set.of(FormFieldType.STRING, FormFieldType.TEXT, FormFieldType.UUID, FormFieldType.TIME).contains(field);
      default -> false;
    };
  }
}
