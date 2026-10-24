package org.simplepoint.plugin.app.form.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFieldConstraints;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;

/** Pure field constraint projection shared by governing versions and surviving historical pages. */
final class FormCalculationFieldConstraints {
  private FormCalculationFieldConstraints() {
  }

  static Map<String, RecordFieldConstraints> compile(final List<FormFieldDefinition> fields) {
    Map<String, RecordFieldConstraints> constraints = new LinkedHashMap<>();
    for (var field : fields) {
      if (!field.persistent() || field.validation() == null && field.choices().isEmpty()) {
        continue;
      }
      FormFieldValidations.definition(field);
      var validation = field.validation();
      var choices = field.choices().stream().map(choice -> new LogicalValue(
          switch (field.type()) {
            case STRING, TEXT -> LogicalValueKind.STRING;
            case INT32, INT64 -> LogicalValueKind.INTEGER;
            default -> LogicalValueKind.valueOf(field.type().name());
          },
          FormChoiceValues.canonical(field.type(), choice.value()))).toList();
      constraints.put(field.fieldId(), new RecordFieldConstraints(validation == null ? null : validation.minLength(),
          validation == null ? null : validation.maxLength(), validation == null ? null : validation.pattern(), choices));
    }
    return constraints;
  }

}
