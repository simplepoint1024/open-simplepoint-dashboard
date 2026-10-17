package org.simplepoint.plugin.app.form.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Stable data-field definition independent from any particular scene component. */
public record FormFieldDefinition(
    String fieldId,
    String storageCode,
    String label,
    String storageLabel,
    FormFieldType type,
    FormStorageMode storageMode,
    boolean submitRequired,
    boolean storageRequired,
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
  public FormFieldDefinition(String fieldId, String storageCode, String label, String storageLabel, FormFieldType type,
      FormStorageMode storageMode, boolean submitRequired, boolean storageRequired,
      Integer length, Integer precision, Integer scale, FormValue defaultValue, List<FormChoiceOption> choices,
      FormFieldWritePolicy writePolicy, FormFieldValidation validation) {
    this(fieldId, storageCode, label, storageLabel, type, storageMode, submitRequired, storageRequired, length, precision, scale, defaultValue, choices, writePolicy, validation, null);
  }

  /** Keeps choice metadata immutable. */
  public FormFieldDefinition {
    choices = choices == null ? List.of() : List.copyOf(choices);
    writePolicy = writePolicy == FormFieldWritePolicy.EDITABLE ? null : writePolicy;
  }

  /** Preserves the v1 default and its canonical checksum for existing definitions. */
  public FormFieldDefinition(
      String fieldId, String storageCode, String label, String storageLabel, FormFieldType type,
      FormStorageMode storageMode, boolean submitRequired, boolean storageRequired,
      Integer length, Integer precision, Integer scale, FormValue defaultValue, List<FormChoiceOption> choices
  ) {
    this(fieldId, storageCode, label, storageLabel, type, storageMode, submitRequired, storageRequired,
        length, precision, scale, defaultValue, choices, null);
  }

  /** Backward-compatible constructor for definitions without business validation. */
  public FormFieldDefinition(
      String fieldId, String storageCode, String label, String storageLabel, FormFieldType type,
      FormStorageMode storageMode, boolean submitRequired, boolean storageRequired,
      Integer length, Integer precision, Integer scale, FormValue defaultValue, List<FormChoiceOption> choices,
      FormFieldWritePolicy writePolicy
  ) {
    this(fieldId, storageCode, label, storageLabel, type, storageMode, submitRequired, storageRequired,
        length, precision, scale, defaultValue, choices, writePolicy, null);
  }

  /** The single write gate shared by mutations and publication readiness checks. */
  public boolean allowsWrite(FormRecordState state) {
    return effectiveWritePolicy().allows(state) && (access == null || access.allows(state));
  }

  /** Unspecified policies preserve legacy editability. */
  public FormFieldWritePolicy effectiveWritePolicy() {
    return writePolicy == null ? FormFieldWritePolicy.EDITABLE : writePolicy;
  }
}
