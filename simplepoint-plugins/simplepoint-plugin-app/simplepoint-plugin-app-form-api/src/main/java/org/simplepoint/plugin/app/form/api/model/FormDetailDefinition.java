package org.simplepoint.plugin.app.form.api.model;

import java.util.List;

/** Owned one-to-many data definition, independent of the control used to edit its rows. */
public record FormDetailDefinition(
    String detailId,
    String storageCode,
    String label,
    List<FormFieldDefinition> fields,
    List<FormIndexDefinition> indexes,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @org.simplepoint.api.schema.LocalizedField(maxLength = 120)
        org.simplepoint.api.locale.LocalizedText labelLocalization) {
  /** Retains source and snapshot compatibility when translated labels are absent. */
  public FormDetailDefinition(
      String detailId,
      String storageCode,
      String label,
      List<FormFieldDefinition> fields,
      List<FormIndexDefinition> indexes) {
    this(detailId, storageCode, label, fields, indexes, null);
  }

  /** Validates and defensively copies the immutable request or snapshot boundary. */
  public FormDetailDefinition {
    labelLocalization =
        labelLocalization == null
            ? null
            : org.simplepoint.api.locale.LocalizedTexts.validate(labelLocalization, 120, 32);
    fields = fields == null ? List.of() : List.copyOf(fields);
    indexes = indexes == null ? List.of() : List.copyOf(indexes);
  }
}
