package org.simplepoint.plugin.app.form.service;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormCondition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueCondition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueConditionOperator;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;

/**
 * Declaration validation and stable-field translation, shared by publication and reference reads.
 */
final class FormReferenceViewPolicy {
  private FormReferenceViewPolicy() {}

  static Map<String, FormFieldDefinition> validate(
      FormReferenceView view, ApplicationFormDocument target) {
    var fields =
        target.fields().stream()
            .filter(FormFieldDefinition::persistent)
            .collect(
                Collectors.toUnmodifiableMap(FormFieldDefinition::fieldId, Function.identity()));
    if (view == null) {
      throw invalid();
    }
    text(fields.get(view.labelFieldId()));
    view.searchFieldIds().forEach(id -> text(fields.get(id)));
    for (String id : view.displayFieldIds()) {
      if (!fields.containsKey(id) || fields.get(id).type() == FormFieldType.JSON) {
        throw invalid();
      }
    }
    if (view.candidateFilter() != null) {
      FormDocumentValidator.validateRelationCondition(view.candidateFilter(), target.fields());
    }
    return fields;
  }

  static RecordValueCondition condition(
      FormCondition condition, Map<String, FormFieldDefinition> fields) {
    if (condition == null) {
      return null;
    }
    var field = condition.fieldId() == null ? null : fields.get(condition.fieldId());
    if (condition.fieldId() != null && field == null) {
      throw invalid();
    }
    return new RecordValueCondition(
        RecordValueConditionOperator.valueOf(condition.operator().name()),
        field == null ? null : field.storageCode(),
        condition.values().stream()
            .map(
                value ->
                    new LogicalValue(LogicalValueKind.valueOf(value.kind().name()), value.value()))
            .toList(),
        condition.children().stream().map(child -> condition(child, fields)).toList());
  }

  private static void text(FormFieldDefinition field) {
    if (field == null || !Set.of(FormFieldType.STRING, FormFieldType.TEXT).contains(field.type())) {
      throw invalid();
    }
  }

  private static ApplicationFormException invalid() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.DOCUMENT_INVALID,
        "Reference presentation requires compatible persistent target fields");
  }
}
