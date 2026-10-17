package org.simplepoint.plugin.app.form.service;

import java.util.ArrayList;
import java.util.List;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordJsonSemantics;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueCondition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueConditionOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordViolationQuery;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;

/** Compiles scalar choices using the same canonical identities as record submission. */
final class FormPublicationChoiceChecks {

  private FormPublicationChoiceChecks() {
  }

  static RecordViolationQuery compile(final String model, final String app, final String form,
      final RecordSchemaReference schema, final FormFieldDefinition field) {
    LogicalValueKind kind = switch (field.type()) {
      case STRING, TEXT -> LogicalValueKind.STRING;
      case INT32, INT64 -> LogicalValueKind.INTEGER;
      default -> LogicalValueKind.valueOf(field.type().name());
    };
    List<LogicalValue> values = field.choices().stream()
        .map(choice -> new LogicalValue(kind, FormChoiceValues.canonical(field.type(), choice.value())))
        .distinct().sorted(java.util.Comparator.comparing(LogicalValue::canonicalValue)).toList();
    List<RecordValueCondition> allowed = new ArrayList<>();
    // Form choices allow 200 values; each typed IN is deliberately limited to 100.
    for (int start = 0; start < values.size(); start += 100) {
      allowed.add(new RecordValueCondition(RecordValueConditionOperator.IN, field.storageCode(),
          values.subList(start, Math.min(start + 100, values.size())), List.of()));
    }
    RecordValueCondition valid = allowed.size() == 1 ? allowed.getFirst()
        : new RecordValueCondition(RecordValueConditionOperator.OR, null, List.of(), allowed);
    var violation = new RecordValueCondition(RecordValueConditionOperator.NOT, null, List.of(), List.of(valid));
    // SQL NULL is allowed by choices; blank text is a real value and must not be mistaken for NULL.
    RecordFilter filter = new RecordFilter(RecordFilterOperator.IS_NOT_NULL, field.storageCode(), List.of(), List.of());
    if (field.allowsWrite(FormRecordState.DRAFT)) {
      filter = new RecordFilter(RecordFilterOperator.AND, null, List.of(), List.of(filter,
          new RecordFilter(RecordFilterOperator.EQUALS, FormStorageProjectionCompiler.RECORD_STATE_CODE,
              List.of(FormRecordState.SUBMITTED.name()), List.of())));
    }
    return new RecordViolationQuery(model, app, form, schema, filter, violation, RecordJsonSemantics.STRUCTURAL_V1);
  }
}
