package org.simplepoint.plugin.app.form.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormCondition;
import org.simplepoint.plugin.app.form.api.model.FormConditionOperator;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceContextFilter;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceContextFilter.Source;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceSearchContext;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueCondition;

/** Resolves bounded candidate inputs without granting access or changing write eligibility. */
final class FormReferenceContextPolicy {
  private FormReferenceContextPolicy() {}

  static void validateSources(ApplicationFormDocument source, FormRelationDefinition relation) {
    for (var filter : filters(relation)) {
      if (filter.source() != Source.ORGANIZATION) {
        sourceField(source, relation, filter);
      }
    }
  }

  static void validate(
      ApplicationFormDocument source,
      FormRelationDefinition relation,
      Map<String, FormFieldDefinition> targetFields) {
    for (var filter : filters(relation)) {
      var target = targetFields.get(filter.targetFieldId());
      if (target == null || !target.persistent() || target.type() == FormFieldType.JSON) {
        throw invalid();
      }
      if (filter.source() == Source.ORGANIZATION) {
        if (!Set.of(FormFieldType.STRING, FormFieldType.TEXT, FormFieldType.UUID)
            .contains(target.type())) {
          throw invalid();
        }
      } else {
        var field = sourceField(source, relation, filter);
        if (field.type() != target.type() && !(text(field.type()) && text(target.type()))) {
          throw invalid();
        }
      }
      if (!Set.of(FormConditionOperator.EQUALS, FormConditionOperator.NOT_EQUALS)
              .contains(filter.operator())
          && !Set.of(
                  FormFieldType.INT32,
                  FormFieldType.INT64,
                  FormFieldType.DECIMAL,
                  FormFieldType.DATE,
                  FormFieldType.TIME,
                  FormFieldType.DATETIME)
              .contains(target.type())) {
        throw invalid();
      }
    }
  }

  static Result resolve(
      ApplicationFormDocument source,
      FormRelationDefinition relation,
      Map<String, FormFieldDefinition> targetFields,
      FormReferenceSearchContext context,
      String organization,
      BiConsumer<Source, FormFieldDefinition> authorize) {
    validate(source, relation, targetFields);
    var inputs = context == null ? new FormReferenceSearchContext(null, null) : context;
    requireDeclaredInputs(inputs.fields(), relation, Source.FORM);
    requireDeclaredInputs(inputs.row(), relation, Source.ROW);
    boolean ready = true;
    var conditions = new ArrayList<FormCondition>();
    for (var filter : filters(relation)) {
      FormValue value;
      var target = targetFields.get(filter.targetFieldId());
      if (filter.source() == Source.ORGANIZATION) {
        value =
            organization == null || organization.isBlank()
                ? null
                : new FormValue(
                    target.type() == FormFieldType.UUID ? FormValueKind.UUID : FormValueKind.STRING,
                    organization);
      } else {
        var field = sourceField(source, relation, filter);
        authorize.accept(filter.source(), field);
        value =
            (filter.source() == Source.FORM ? inputs.fields() : inputs.row())
                .get(filter.sourceFieldId());
        if (value != null && value.kind() != FormValueKind.NULL) {
          FormDocumentValidator.validateRelationCondition(
              predicate(filter.operator(), field.fieldId(), value), List.of(field));
        }
      }
      if (value == null
          || value.kind() == FormValueKind.NULL
          || value.kind() == FormValueKind.STRING && value.value().isEmpty()) {
        ready = false;
        continue;
      }
      var condition = predicate(filter.operator(), target.fieldId(), value);
      FormDocumentValidator.validateRelationCondition(condition, List.of(target));
      conditions.add(condition);
    }
    if (!ready) {
      return new Result(false, null);
    }
    var fixed =
        relation.referenceView() == null ? null : relation.referenceView().candidateFilter();
    if (fixed != null) {
      conditions.add(fixed);
    }
    FormCondition combined =
        conditions.isEmpty()
            ? null
            : conditions.size() == 1
                ? conditions.getFirst()
                : new FormCondition(FormConditionOperator.AND, null, List.of(), conditions);
    return new Result(true, FormReferenceViewPolicy.condition(combined, targetFields));
  }

  static FormFieldDefinition sourceField(
      ApplicationFormDocument document,
      FormRelationDefinition relation,
      FormReferenceContextFilter filter) {
    var fields = document.fields();
    if (filter.source() == Source.ROW) {
      if (relation.sourceField() == null || relation.sourceField().detailId() == null) {
        throw invalid();
      }
      fields =
          document.details().stream()
              .filter(detail -> detail.detailId().equals(relation.sourceField().detailId()))
              .findFirst()
              .orElseThrow(FormReferenceContextPolicy::invalid)
              .fields();
    }
    return fields.stream()
        .filter(
            field ->
                field.fieldId().equals(filter.sourceFieldId())
                    && field.persistent()
                    && field.type() != FormFieldType.JSON)
        .findFirst()
        .orElseThrow(FormReferenceContextPolicy::invalid);
  }

  private static List<FormReferenceContextFilter> filters(FormRelationDefinition relation) {
    return relation.referenceView() == null ? List.of() : relation.referenceView().contextFilters();
  }

  private static void requireDeclaredInputs(
      Map<String, FormValue> values, FormRelationDefinition relation, Source source) {
    var allowed =
        filters(relation).stream()
            .filter(filter -> filter.source() == source)
            .map(FormReferenceContextFilter::sourceFieldId)
            .collect(Collectors.toSet());
    if (!allowed.containsAll(values.keySet())) {
      throw invalid();
    }
  }

  private static FormCondition predicate(
      FormConditionOperator operator, String field, FormValue value) {
    return new FormCondition(operator, field, List.of(value), List.of());
  }

  private static boolean text(FormFieldType type) {
    return type == FormFieldType.STRING || type == FormFieldType.TEXT;
  }

  private static ApplicationFormException invalid() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.DOCUMENT_INVALID,
        "Reference candidate context requires declared compatible readable fields");
  }

  record Result(boolean ready, RecordValueCondition condition) {}
}
