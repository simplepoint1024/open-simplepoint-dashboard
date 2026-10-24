package org.simplepoint.plugin.app.form.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFieldWriteRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationField;

/** Projects trusted operation/lifecycle policies; no UI or APP enum escapes into DNA. */
final class FormCalculationWriteProjection {
  private FormCalculationWriteProjection() {}

  static List<RecordFieldWriteRule> compile(final ApplicationFormDocument document) {
    List<RecordFieldWriteRule> rules = new ArrayList<>();
    append(rules, "parent", document.fields());
    document.details().forEach(detail -> append(rules, detail.detailId(), detail.fields()));
    return List.copyOf(rules);
  }

  private static void append(final List<RecordFieldWriteRule> rules, final String scope, final List<FormFieldDefinition> fields) {
    fields.stream().filter(field -> field.persistent() && field.calculation() == null).forEach(field -> {
      var states = Arrays.stream(FormRecordState.values()).filter(field::allowsWrite).map(Enum::name).toList();
      if (!field.allowsWrite(null) || states.size() != FormRecordState.values().length) {
        rules.add(new RecordFieldWriteRule(new RecordValidationField(scope, field.fieldId()), field.allowsWrite(null), states));
      }
    });
  }
}
