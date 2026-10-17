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
    @JsonInclude(JsonInclude.Include.NON_NULL) FormFieldAccess access
) {

  /** Preserves legacy documents without operation policies. */
  public ApplicationFormRuntimeFieldDefinition(String fieldId, String label, FormFieldType type, boolean submitRequired, Integer length,
      Integer precision, Integer scale, FormValue defaultValue, List<FormChoiceOption> choices,
      FormFieldWritePolicy writePolicy, FormFieldValidation validation) {
    this(fieldId, label, type, submitRequired, length, precision, scale, defaultValue, choices, writePolicy, validation, null);
  }

  /** Keeps choice metadata immutable. */
  public ApplicationFormRuntimeFieldDefinition {
    choices = choices == null ? List.of() : List.copyOf(choices);
    writePolicy = writePolicy == FormFieldWritePolicy.EDITABLE ? null : writePolicy;
  }

  /** Preserves callers rendering legacy editable fields. */
  public ApplicationFormRuntimeFieldDefinition(
      String fieldId, String label, FormFieldType type, boolean submitRequired, Integer length,
      Integer precision, Integer scale, FormValue defaultValue, List<FormChoiceOption> choices
  ) {
    this(fieldId, label, type, submitRequired, length, precision, scale, defaultValue, choices, null);
  }
  /** Backward-compatible constructor for fields without business validation. */
  public ApplicationFormRuntimeFieldDefinition(
      String fieldId, String label, FormFieldType type, boolean submitRequired, Integer length,
      Integer precision, Integer scale, FormValue defaultValue, List<FormChoiceOption> choices,
      FormFieldWritePolicy writePolicy
  ) {
    this(fieldId, label, type, submitRequired, length, precision, scale, defaultValue, choices, writePolicy, null);
  }
}
