package org.simplepoint.plugin.app.form.service;

import java.util.Objects;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormChoiceOption;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;

/**
 * Prevents implicitly dropping or migrating owned data until child migration review is available.
 */
final class FormDetailEvolutionPolicy {
  private FormDetailEvolutionPolicy() {}

  static void validate(
      ApplicationFormDocument previous,
      ApplicationFormDocument next,
      FormStorageProjectionCompiler compiler) {
    for (var old : previous.details()) {
      var replacement =
          next.details().stream()
              .filter(detail -> detail.detailId().equals(old.detailId()))
              .findFirst();
      if (replacement.isEmpty()
          || !Objects.equals(old.storageCode(), replacement.get().storageCode())
          || !compiler
              .compileDetail(old, "owner")
              .checksum()
              .equals(compiler.compileDetail(replacement.get(), "owner").checksum())
          || !old.fields().stream()
              .filter(FormFieldDefinition::persistent)
              .map(FormDetailEvolutionPolicy::withoutPresentation)
              .toList()
              .equals(
                  replacement.get().fields().stream()
                      .filter(FormFieldDefinition::persistent)
                      .map(FormDetailEvolutionPolicy::withoutPresentation)
                      .toList())) {
        throw new ApplicationFormException(
            ApplicationFormErrorCode.PUBLICATION_REVIEW_REQUIRED,
            "Existing detail definitions must remain unchanged; add a new collection for a new"
                + " structure");
      }
    }
  }

  /**
   * Display labels do not migrate owned rows; every storage, value and behavioral property still
   * participates.
   */
  private static FormFieldDefinition withoutPresentation(FormFieldDefinition field) {
    return new FormFieldDefinition(
        field.fieldId(),
        field.storageCode(),
        field.storageLabel(),
        field.storageLabel(),
        field.type(),
        field.storageMode(),
        field.submitRequired(),
        field.storageRequired(),
        field.length(),
        field.precision(),
        field.scale(),
        field.defaultValue(),
        field.choices().stream().map(choice -> new FormChoiceOption(choice.value(), "")).toList(),
        field.writePolicy(),
        field.validation(),
        field.access(),
        field.calculation(),
        null);
  }
}
