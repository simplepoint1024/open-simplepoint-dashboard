package org.simplepoint.plugin.app.form.api.model;

/** One stable choice presented by a selection field. */
public record FormChoiceOption(
    String value,
    String label,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @org.simplepoint.api.schema.LocalizedField(maxLength = 120)
        org.simplepoint.api.locale.LocalizedText labelLocalization) {
  /** Retains source and snapshot compatibility when translated labels are absent. */
  public FormChoiceOption(String value, String label) {
    this(value, label, null);
  }

  public FormChoiceOption {
    labelLocalization =
        labelLocalization == null
            ? null
            : org.simplepoint.api.locale.LocalizedTexts.validate(labelLocalization, 120, 32);
  }
}
