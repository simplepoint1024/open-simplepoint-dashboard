package org.simplepoint.plugin.app.form.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormCondition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFieldConstraints;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationCondition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationField;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueConditionOperator;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;

/** Projects published form semantics to stable, platform-neutral post-calculation invariants. */
final class FormCalculationValidationProjection {
  private static final ObjectMapper IDENTITIES = new ObjectMapper();

  private FormCalculationValidationProjection() {
  }

  static List<RecordValidationRule> compile(final ApplicationFormDocument document) {
    List<RecordValidationRule> rules = new ArrayList<>();
    requirements(document, "parent", rules);
    rules.addAll(FormCalculationComponentConstraints.compile(document));
    document.details().forEach(detail -> requirements(new ApplicationFormDocument("1", detail.fields(), detail.indexes(), List.of(), List.of()),
        detail.detailId(), rules));
    FormAggregateValidation.validateDefinition(document);
    for (var rule : document.constraints()) {
      var kind = switch (rule.kind()) {
        case RANGE, DETAIL_RANGE -> RecordValidationRule.Kind.RANGE;
        case AFTER -> RecordValidationRule.Kind.AFTER;
        case DETAIL_COUNT -> RecordValidationRule.Kind.DETAIL_COUNT;
        case DETAIL_SUM -> RecordValidationRule.Kind.DETAIL_SUM;
      };
      var field = switch (rule.kind()) {
        case DETAIL_COUNT -> null;
        case DETAIL_RANGE -> reference(document, rule.detailId(), rule.detailFieldId());
        default -> reference(document, "parent", rule.fieldId());
      };
      var other = switch (rule.kind()) {
        case AFTER -> reference(document, "parent", rule.otherFieldId());
        case DETAIL_SUM -> reference(document, rule.detailId(), rule.detailFieldId());
        default -> null;
      };
      boolean range = kind == RecordValidationRule.Kind.RANGE || kind == RecordValidationRule.Kind.DETAIL_COUNT;
      rules.add(new RecordValidationRule(identity("aggregate", rule.constraintId()), kind, field, other,
          kind == RecordValidationRule.Kind.DETAIL_COUNT ? rule.detailId() : null,
          range ? rule.minimum() : null, range ? rule.maximum() : null, state(FormRecordState.SUBMITTED)));
    }
    return List.copyOf(rules);
  }

  /** Historical pages retain their own root requirements; details and aggregate rules are governed once. */
  static List<RecordValidationRule> pageRequirements(final ApplicationFormDocument document) {
    List<RecordValidationRule> rules = new ArrayList<>();
    requirements(document, "parent", rules);
    rules.addAll(FormCalculationComponentConstraints.compile(document));
    var constraints = FormCalculationFieldConstraints.compile(document.fields());
    for (var field : document.fields()) {
      var constraint = constraints.get(field.fieldId());
      if (constraint == null) {
        continue;
      }
      // Text rules apply to every final value; choices retain the historical draft admission policy.
      if (constraint.minLength() != null || constraint.maxLength() != null || constraint.pattern() != null) {
        rules.add(fieldRule(field, new RecordFieldConstraints(constraint.minLength(), constraint.maxLength(), constraint.pattern(), List.of()),
            constant(true), "text"));
      }
      if (!constraint.choices().isEmpty()) {
        var when = field.allowsWrite(FormRecordState.DRAFT) ? state(FormRecordState.SUBMITTED) : constant(true);
        rules.add(fieldRule(field, new RecordFieldConstraints(null, null, null, constraint.choices()), when, "choices"));
      }
    }
    return List.copyOf(rules);
  }

  private static RecordValidationRule fieldRule(final FormFieldDefinition field, final RecordFieldConstraints constraint,
      final RecordValidationCondition when, final String policy) {
    try {
      // Hash explicit JSON values, never UI labels, mutable columns or Java diagnostic strings.
      String content = IDENTITIES.writeValueAsString(List.of(field.fieldId(), policy, constraint, when));
      return new RecordValidationRule(identity("field", content), RecordValidationRule.Kind.FIELD,
          new RecordValidationField("parent", field.fieldId()), null, null, null, null, when, constraint);
    } catch (JsonProcessingException failure) {
      throw new IllegalArgumentException("Cannot encode published field validation identity", failure);
    }
  }

  /** The union of requiredness predicates preserves every surviving page without duplicating field rules. */
  static List<RecordValidationRule> merge(final List<RecordValidationRule> left, final List<RecordValidationRule> right) {
    Map<String, RecordValidationRule> rules = new LinkedHashMap<>();
    for (var source : List.of(left, right)) {
      for (var rule : source) {
        var previous = rules.putIfAbsent(rule.ruleId(), rule);
        if (previous != null && !previous.equals(rule)) {
          if (previous.kind() != RecordValidationRule.Kind.REQUIRED || rule.kind() != RecordValidationRule.Kind.REQUIRED
              || !previous.field().equals(rule.field())) {
            throw new IllegalArgumentException("Conflicting published validation rule identities");
          }
          rules.put(rule.ruleId(), new RecordValidationRule(rule.ruleId(), rule.kind(), rule.field(), null, null, null, null,
              group(RecordValueConditionOperator.OR, List.of(previous.when(), rule.when()))));
        }
        if (rules.size() > 2000) {
          throw new IllegalArgumentException("Active page validation rule budget exceeded");
        }
      }
    }
    // Reuse the neutral contract's whole-definition budget; no model is installed here.
    return new org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition("1", Map.of(), Map.of(), null,
        List.copyOf(rules.values())).validationsOrEmpty();
  }

  private static void requirements(final ApplicationFormDocument document, final String scope, final List<RecordValidationRule> target) {
    var fields = document.fields().stream().collect(Collectors.toMap(FormFieldDefinition::fieldId, Function.identity()));
    for (var requirement : FormSubmissionRequirements.compileCalculated(document)) {
      for (var state : FormRecordState.values()) {
        var required = predicate(requirement.forState(state).predicate(), scope, fields);
        if (required.operator() != RecordValueConditionOperator.FALSE) {
          target.add(new RecordValidationRule(identity("required", scope + "/" + requirement.field().fieldId() + "/" + state),
              RecordValidationRule.Kind.REQUIRED, new RecordValidationField(scope, requirement.field().fieldId()),
              null, null, null, null, group(RecordValueConditionOperator.AND, List.of(state(state), required))));
        }
      }
    }
  }

  private static RecordValidationCondition predicate(final FormSubmissionRequirements.Predicate predicate, final String scope,
      final Map<String, FormFieldDefinition> fields) {
    return switch (predicate) {
      case FormSubmissionRequirements.Constant value -> constant(value == FormSubmissionRequirements.Constant.TRUE);
      case FormSubmissionRequirements.Condition value -> condition(value.condition(), scope, fields);
      case FormSubmissionRequirements.Negated value -> group(RecordValueConditionOperator.NOT, List.of(predicate(value.child(), scope, fields)));
      case FormSubmissionRequirements.All value -> group(RecordValueConditionOperator.AND,
          value.children().stream().map(child -> predicate(child, scope, fields)).toList());
      case FormSubmissionRequirements.Any value -> group(RecordValueConditionOperator.OR,
          value.children().stream().map(child -> predicate(child, scope, fields)).toList());
    };
  }

  private static RecordValidationCondition condition(final FormCondition condition, final String scope,
      final Map<String, FormFieldDefinition> fields) {
    var operator = RecordValueConditionOperator.valueOf(condition.operator().name());
    if (operator.group()) {
      return group(operator, condition.children().stream().map(child -> condition(child, scope, fields)).toList());
    }
    var field = fields.get(condition.fieldId());
    if (field == null) {
      throw new IllegalArgumentException("Calculation validation condition references an unknown field");
    }
    if (!field.persistent()) {
      Map<String, Object> values = new HashMap<>();
      values.put(field.fieldId(), FormRecordPayloadCompiler.value(field.defaultValue()));
      return constant(FormRuleEvaluator.matches(condition, values, Map.of(field.fieldId(), field.type())));
    }
    return new RecordValidationCondition(operator, new RecordValidationField(scope, field.fieldId()), condition.values().stream()
        .map(value -> new LogicalValue(LogicalValueKind.valueOf(value.kind().name()), value.value())).toList(), List.of());
  }

  private static RecordValidationField reference(final ApplicationFormDocument document, final String scope, final String id) {
    var fields = "parent".equals(scope) ? document.fields() : document.details().stream()
        .filter(detail -> detail.detailId().equals(scope)).findFirst().orElseThrow().fields();
    if (fields.stream().noneMatch(field -> field.fieldId().equals(id) && field.persistent())) {
      throw new IllegalArgumentException("Calculated aggregate constraints require persistent fields");
    }
    return new RecordValidationField(scope, id);
  }

  private static RecordValidationCondition state(final FormRecordState state) {
    return new RecordValidationCondition(RecordValueConditionOperator.EQUALS,
        new RecordValidationField("parent", FormStorageProjectionCompiler.RECORD_STATE_FIELD_ID),
        List.of(new LogicalValue(LogicalValueKind.STRING, state.name())), List.of());
  }

  private static RecordValidationCondition constant(final boolean value) {
    return new RecordValidationCondition(value ? RecordValueConditionOperator.TRUE : RecordValueConditionOperator.FALSE, null, List.of(), List.of());
  }

  private static RecordValidationCondition group(final RecordValueConditionOperator operator, final List<RecordValidationCondition> children) {
    if (operator == RecordValueConditionOperator.NOT) {
      var child = children.getFirst();
      return child.operator().constant() ? constant(child.operator() == RecordValueConditionOperator.FALSE)
          : new RecordValidationCondition(operator, null, List.of(), children);
    }
    var neutral = operator == RecordValueConditionOperator.AND ? RecordValueConditionOperator.TRUE : RecordValueConditionOperator.FALSE;
    if (children.stream().anyMatch(child -> child.operator().constant() && child.operator() != neutral)) {
      return constant(neutral == RecordValueConditionOperator.FALSE);
    }
    var effective = children.stream().flatMap(child -> child.operator() == operator ? child.children().stream() : java.util.stream.Stream.of(child))
        .filter(child -> child.operator() != neutral).distinct().toList();
    return effective.isEmpty() ? constant(neutral == RecordValueConditionOperator.TRUE) : effective.size() == 1 ? effective.getFirst()
        : new RecordValidationCondition(operator, null, List.of(), effective);
  }

  static String identity(final String kind, final String value) {
    return kind + "-" + UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }
}
