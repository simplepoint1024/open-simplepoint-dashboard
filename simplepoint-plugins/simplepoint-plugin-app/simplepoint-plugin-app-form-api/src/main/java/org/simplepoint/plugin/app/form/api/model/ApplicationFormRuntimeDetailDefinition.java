package org.simplepoint.plugin.app.form.api.model;

import java.util.List;

/** Browser-safe owned collection definition; physical model identity never leaves the server. */
public record ApplicationFormRuntimeDetailDefinition(
    String detailId,
    String label,
    List<ApplicationFormRuntimeFieldDefinition> fields,
    List<String> readOnlyFieldIds,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @org.simplepoint.api.schema.LocalizedField(maxLength = 120)
        org.simplepoint.api.locale.LocalizedText labelLocalization) {
  /** Retains source and snapshot compatibility when translated labels are absent. */
  public ApplicationFormRuntimeDetailDefinition(
      String detailId,
      String label,
      List<ApplicationFormRuntimeFieldDefinition> fields,
      List<String> readOnlyFieldIds) {
    this(detailId, label, fields, readOnlyFieldIds, null);
  }

  /** Validates and defensively copies the immutable request or snapshot boundary. */
  public ApplicationFormRuntimeDetailDefinition {
    labelLocalization =
        labelLocalization == null
            ? null
            : org.simplepoint.api.locale.LocalizedTexts.validate(labelLocalization, 120, 32);
    fields = List.copyOf(fields);
    readOnlyFieldIds = List.copyOf(readOnlyFieldIds);
  }
}
