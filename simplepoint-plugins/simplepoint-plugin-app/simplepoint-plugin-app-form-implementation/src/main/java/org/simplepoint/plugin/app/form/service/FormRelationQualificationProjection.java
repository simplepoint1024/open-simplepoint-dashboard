package org.simplepoint.plugin.app.form.service;

import java.util.List;
import org.simplepoint.plugin.app.form.api.model.FormCondition;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationQualification;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationCondition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationField;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueConditionOperator;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;

/**
 * Compiles immutable qualification into DNA admission without importing scene candidate filters.
 */
final class FormRelationQualificationProjection {
  private FormRelationQualificationProjection() {}

  static RecordValidationCondition eligibility(FormRelationQualification qualification) {
    return qualification == null ? null : condition(qualification.condition());
  }

  static RecordValidationCondition recheck(FormRelationQualification qualification) {
    if (qualification == null) {
      return null;
    }
    return switch (qualification.recheck()) {
      case ON_CHANGE ->
          null; // DNA always checks new and changed references against server old values.
      case ALWAYS ->
          new RecordValidationCondition(
              RecordValueConditionOperator.TRUE, null, List.of(), List.of());
      case ON_SUBMIT ->
          new RecordValidationCondition(
              RecordValueConditionOperator.EQUALS,
              new RecordValidationField(
                  "parent", FormStorageProjectionCompiler.RECORD_STATE_FIELD_ID),
              List.of(new LogicalValue(LogicalValueKind.STRING, FormRecordState.SUBMITTED.name())),
              List.of());
    };
  }

  private static RecordValidationCondition condition(FormCondition condition) {
    return new RecordValidationCondition(
        RecordValueConditionOperator.valueOf(condition.operator().name()),
        condition.fieldId() == null
            ? null
            : new RecordValidationField("parent", condition.fieldId()),
        condition.values().stream()
            .map(
                value ->
                    new LogicalValue(LogicalValueKind.valueOf(value.kind().name()), value.value()))
            .toList(),
        condition.children().stream().map(FormRelationQualificationProjection::condition).toList());
  }
}
