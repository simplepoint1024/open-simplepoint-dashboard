package org.simplepoint.plugin.app.form.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Browser-safe field metadata required to render an authorized immutable form. */
public record ApplicationFormRuntimeFieldDefinition(
    String fieldId,
    String label,
    FormFieldType type,
    boolean submitRequired,
    Integer length,
    Integer precision,
    Integer scale,
    FormValue defaultValue,
    List<FormChoiceOption> choices,
    @JsonInclude(JsonInclude.Include.NON_NULL) FormFieldWritePolicy writePolicy,
    @JsonInclude(JsonInclude.Include.NON_NULL) FormFieldValidation validation,
    @JsonInclude(JsonInclude.Include.NON_NULL) FormFieldAccess access,
    @JsonInclude(JsonInclude.Include.NON_NULL) Boolean calculated,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @org.simplepoint.api.schema.LocalizedField(maxLength = 120)
        org.simplepoint.api.locale.LocalizedText labelLocalization) {
  /** Retains source and snapshot compatibility when translated labels are absent. */
  public ApplicationFormRuntimeFieldDefinition(
      String fieldId,
      String label,
      FormFieldType type,
      boolean submitRequired,
      Integer length,
      Integer precision,
      Integer scale,
      FormValue defaultValue,
      List<FormChoiceOption> choices,
      FormFieldWritePolicy writePolicy,
      FormFieldValidation validation,
      FormFieldAccess access,
      Boolean calculated) {
    this(
        fieldId,
        label,
        type,
        submitRequired,
        length,
        precision,
        scale,
        defaultValue,
        choices,
        writePolicy,
        validation,
        access,
        calculated,
        null);
  }

  /** Older runtime snapshots contain no calculation capability marker. */
  public ApplicationFormRuntimeFieldDefinition(
      String fieldId,
      String label,
      FormFieldType type,
      boolean submitRequired,
      Integer length,
      Integer precision,
      Integer scale,
      FormValue defaultValue,
      List<FormChoiceOption> choices,
      FormFieldWritePolicy writePolicy,
      FormFieldValidation validation,
      FormFieldAccess access) {
    this(
        fieldId,
        label,
        type,
        submitRequired,
        length,
        precision,
        scale,
        defaultValue,
        choices,
        writePolicy,
        validation,
        access,
        null);
  }

  /** Preserves legacy documents without operation policies. */
  public ApplicationFormRuntimeFieldDefinition(
      String fieldId,
      String label,
      FormFieldType type,
      boolean submitRequired,
      Integer length,
      Integer precision,
      Integer scale,
      FormValue defaultValue,
      List<FormChoiceOption> choices,
      FormFieldWritePolicy writePolicy,
      FormFieldValidation validation) {
    this(
        fieldId,
        label,
        type,
        submitRequired,
        length,
        precision,
        scale,
        defaultValue,
        choices,
        writePolicy,
        validation,
        null);
  }

  /** Keeps choice metadata immutable. */
  public ApplicationFormRuntimeFieldDefinition {
    labelLocalization =
        labelLocalization == null
            ? null
            : org.simplepoint.api.locale.LocalizedTexts.validate(labelLocalization, 120, 32);
    calculated = Boolean.TRUE.equals(calculated) ? Boolean.TRUE : null;
    choices = choices == null ? List.of() : List.copyOf(choices);
    writePolicy = writePolicy == FormFieldWritePolicy.EDITABLE ? null : writePolicy;
  }

  /** Preserves callers rendering legacy editable fields. */
  public ApplicationFormRuntimeFieldDefinition(
      String fieldId,
      String label,
      FormFieldType type,
      boolean submitRequired,
      Integer length,
      Integer precision,
      Integer scale,
      FormValue defaultValue,
      List<FormChoiceOption> choices) {
    this(
        fieldId,
        label,
        type,
        submitRequired,
        length,
        precision,
        scale,
        defaultValue,
        choices,
        null);
  }

  /** Backward-compatible constructor for fields without business validation. */
  public ApplicationFormRuntimeFieldDefinition(
      String fieldId,
      String label,
      FormFieldType type,
      boolean submitRequired,
      Integer length,
      Integer precision,
      Integer scale,
      FormValue defaultValue,
      List<FormChoiceOption> choices,
      FormFieldWritePolicy writePolicy) {
    this(
        fieldId,
        label,
        type,
        submitRequired,
        length,
        precision,
        scale,
        defaultValue,
        choices,
        writePolicy,
        null);
  }
}
