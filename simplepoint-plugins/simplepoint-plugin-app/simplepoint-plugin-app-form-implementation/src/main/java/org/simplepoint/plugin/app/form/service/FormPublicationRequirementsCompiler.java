package org.simplepoint.plugin.app.form.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormCondition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.service.FormSubmissionRequirements.All;
import org.simplepoint.plugin.app.form.service.FormSubmissionRequirements.Any;
import org.simplepoint.plugin.app.form.service.FormSubmissionRequirements.Condition;
import org.simplepoint.plugin.app.form.service.FormSubmissionRequirements.Constant;
import org.simplepoint.plugin.app.form.service.FormSubmissionRequirements.Negated;
import org.simplepoint.plugin.app.form.service.FormSubmissionRequirements.Predicate;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCompletenessQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordJsonSemantics;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueCondition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueConditionOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordViolationQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;

/** Compiles the submission invariant into typed DNA checks without SQL or business-row reads. */
final class FormPublicationRequirementsCompiler {

  private FormPublicationRequirementsCompiler() {
  }

  static Plan compile(final String modelId, final String appId, final String formId,
      final Iterable<Version> pages, final ApplicationFormDocument governing) {
    Map<Group, TreeSet<String>> groups = new LinkedHashMap<>();
    Map<RecordViolationQuery, String> choices = new LinkedHashMap<>();
    try {
      boolean observedPage = false;
      for (Version page : pages) {
        observedPage = true;
        ApplicationFormDocument document = FormWritePolicyProjection.apply(page.document(), governing);
        Map<String, FormFieldDefinition> fields = document.fields().stream()
            .collect(Collectors.toUnmodifiableMap(FormFieldDefinition::fieldId, Function.identity()));
        for (var requirement : FormSubmissionRequirements.compile(document)) {
          if (!requirement.field().choices().isEmpty()) {
            var check = FormPublicationChoiceChecks.compile(modelId, appId, formId, page.schema(), requirement.field());
            choices.computeIfAbsent(check, ignored -> "choice-" + choices.size());
            if (choices.size() > 100 || choices.keySet().stream().mapToInt(query -> query.condition().complexity()).sum() > 16_384) {
              throw new IllegalArgumentException("Publication choices exceed the inspection budget");
            }
          }
          RecordValueCondition draft = predicate(requirement.forState(FormRecordState.DRAFT).predicate(), fields);
          RecordValueCondition submitted = predicate(requirement.forState(FormRecordState.SUBMITTED).predicate(), fields);
          if (draft.equals(submitted)) {
            add(groups, page.schema(), null, draft, requirement.field().storageCode());
          } else {
            add(groups, page.schema(), FormRecordState.DRAFT, draft, requirement.field().storageCode());
            add(groups, page.schema(), FormRecordState.SUBMITTED, submitted, requirement.field().storageCode());
          }
        }
      }
      if (!observedPage) {
        throw new IllegalArgumentException("A candidate page definition is required");
      }
      Map<String, RecordCompletenessQuery> checks = new LinkedHashMap<>();
      for (var entry : groups.entrySet()) {
        Group group = entry.getKey();
        List<String> fields = new ArrayList<>(entry.getValue());
        for (int start = 0; start < fields.size(); start += 100) {
          RecordFilter filter = group.state() == null ? null : new RecordFilter(RecordFilterOperator.EQUALS,
              FormStorageProjectionCompiler.RECORD_STATE_CODE, List.of(group.state().name()), List.of());
          checks.put("required-" + checks.size(), new RecordCompletenessQuery(modelId, appId, formId, group.schema(),
              fields.subList(start, Math.min(start + 100, fields.size())), filter,
              is(group.condition(), true) ? null : group.condition(), RecordJsonSemantics.STRUCTURAL_V1));
        }
      }
      Map<String, RecordViolationQuery> valueChecks = new LinkedHashMap<>();
      choices.forEach((query, key) -> valueChecks.put(key, query));
      RecordWritePolicyPublicationCommand.validateBudget(checks, valueChecks);
      return new Plan(checks, valueChecks);
    } catch (IllegalArgumentException failure) {
      throw new ApplicationFormException(ApplicationFormErrorCode.DOCUMENT_INVALID,
          "The form publication requirements exceed the supported inspection budget", failure);
    }
  }

  /** Separate typed checks retain their distinct missing-value and invalid-value report semantics. */
  record Plan(Map<String, RecordCompletenessQuery> checks, Map<String, RecordViolationQuery> valueChecks) {
    Plan {
      checks = Map.copyOf(checks);
      valueChecks = Map.copyOf(valueChecks);
    }
  }

  private static void add(final Map<Group, TreeSet<String>> groups, final RecordSchemaReference schema,
      final FormRecordState state, final RecordValueCondition condition, final String field) {
    if (!is(condition, false)) {
      RecordValueCondition.bounded(condition);
      groups.computeIfAbsent(new Group(schema, state, condition), ignored -> new TreeSet<>()).add(field);
      // Bound retained state while reading historical versions; never accumulate an unbounded plan first.
      if (groups.size() > 100 || groups.values().stream().mapToInt(TreeSet::size).sum() > 1000
          || groups.keySet().stream().mapToInt(group -> group.condition().complexity()).sum() > 16_384) {
        throw new IllegalArgumentException("Publication requirements exceed the inspection budget");
      }
    }
  }

  private static RecordValueCondition predicate(final Predicate predicate, final Map<String, FormFieldDefinition> fields) {
    return switch (predicate) {
      case Constant value -> constant(value == Constant.TRUE);
      case Condition value -> condition(value.condition(), fields);
      case Negated value -> not(predicate(value.child(), fields));
      case All value -> group(RecordValueConditionOperator.AND,
          value.children().stream().map(child -> predicate(child, fields)).toList());
      case Any value -> group(RecordValueConditionOperator.OR,
          value.children().stream().map(child -> predicate(child, fields)).toList());
    };
  }

  private static RecordValueCondition condition(final FormCondition condition, final Map<String, FormFieldDefinition> fields) {
    RecordValueConditionOperator operator = RecordValueConditionOperator.valueOf(condition.operator().name());
    if (operator.group()) {
      List<RecordValueCondition> children = condition.children().stream().map(child -> condition(child, fields)).toList();
      return operator == RecordValueConditionOperator.NOT ? not(children.getFirst()) : group(operator, children);
    }
    FormFieldDefinition field = Objects.requireNonNull(fields.get(condition.fieldId()), "condition field");
    if (field.storageMode() == FormStorageMode.TRANSIENT || field.storageMode() == FormStorageMode.COMPUTED) {
      // Historical rows have no transient input. Use the same absent/default value as server submission.
      Map<String, Object> values = new HashMap<>();
      values.put(field.fieldId(), FormRecordPayloadCompiler.value(field.defaultValue()));
      return constant(FormRuleEvaluator.matches(condition, values, Map.of(field.fieldId(), field.type())));
    }
    return new RecordValueCondition(operator, field.storageCode(), condition.values().stream()
        .map(value -> new LogicalValue(LogicalValueKind.valueOf(value.kind().name()), value.value())).toList(), List.of());
  }

  private static RecordValueCondition group(final RecordValueConditionOperator operator, final List<RecordValueCondition> children) {
    boolean conjunction = operator == RecordValueConditionOperator.AND;
    if (children.stream().anyMatch(child -> is(child, !conjunction))) {
      return constant(!conjunction);
    }
    List<RecordValueCondition> effective = children.stream().filter(child -> !is(child, conjunction)).distinct().toList();
    return effective.isEmpty() ? constant(conjunction) : effective.size() == 1 ? effective.getFirst()
        : new RecordValueCondition(operator, null, List.of(), effective);
  }

  private static RecordValueCondition not(final RecordValueCondition child) {
    if (child.operator().constant()) {
      return constant(is(child, false));
    }
    return child.operator() == RecordValueConditionOperator.NOT ? child.children().getFirst()
        : new RecordValueCondition(RecordValueConditionOperator.NOT, null, List.of(), List.of(child));
  }

  private static boolean is(final RecordValueCondition condition, final boolean value) {
    return condition.operator() == (value ? RecordValueConditionOperator.TRUE : RecordValueConditionOperator.FALSE);
  }

  private static RecordValueCondition constant(final boolean value) {
    return new RecordValueCondition(value ? RecordValueConditionOperator.TRUE : RecordValueConditionOperator.FALSE, null, List.of(), List.of());
  }

  /** An effective page keeps its own immutable rules and requested physical projection. */
  record Version(RecordSchemaReference schema, ApplicationFormDocument document) {
    Version {
      Objects.requireNonNull(schema, "schema");
      Objects.requireNonNull(document, "document");
    }
  }

  private record Group(RecordSchemaReference schema, FormRecordState state, RecordValueCondition condition) {
  }
}
