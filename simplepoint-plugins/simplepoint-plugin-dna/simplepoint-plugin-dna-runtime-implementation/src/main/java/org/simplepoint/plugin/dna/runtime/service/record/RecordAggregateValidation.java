package org.simplepoint.plugin.dna.runtime.service.record;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationCondition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationField;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueConditionOperator;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalJsonValues;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;

/** Model-bound invariants evaluated on the final typed aggregate, before any result is written. */
final class RecordAggregateValidation {
  private static final Set<LogicalFieldType> NUMERIC = Set.of(LogicalFieldType.INT32, LogicalFieldType.INT64, LogicalFieldType.DECIMAL);
  private static final Set<LogicalFieldType> TEMPORAL = Set.of(LogicalFieldType.DATE, LogicalFieldType.TIME, LogicalFieldType.DATETIME);
  private final Map<String, Map<String, LogicalFieldDefinition>> fields;
  private final List<Check> checks;

  RecordAggregateValidation(final Map<String, Map<String, LogicalFieldDefinition>> fields, final List<RecordValidationRule> rules) {
    this.fields = fields;
    checks = rules.stream().map(this::compile).toList();
  }

  /** Reuses the typed condition semantics for an installed rule; inputs use stable field IDs. */
  java.util.function.BiPredicate<Map<String, Object>, Map<String, Object>> conditionFor(
      final RecordValidationCondition condition, final String scope) {
    requireScope(scope);
    var predicate = condition(condition, scope);
    return (parent, row) -> predicate.test(new Values(parent, row, Map.of()));
  }

  void validate(final Map<String, Object> parent, final Map<String, List<Map<String, Object>>> details) {
    for (var check : checks) {
      var rows = "parent".equals(check.scope()) ? List.of(parent) : details.get(check.scope());
      for (var row : rows) {
        var context = new Values(parent, row, details);
        if (check.when().test(context) && !check.valid().test(context)) {
          // No submitted values, labels or hidden field identities enter the public error.
          throw invalid("Calculated aggregate violates its published validation rules", null);
        }
      }
    }
  }

  private Check compile(final RecordValidationRule rule) {
    String scope = rule.evaluationScope();
    requireScope(scope);
    var when = condition(rule.when(), scope);
    var field = rule.field() == null ? null : field(rule.field());
    var other = rule.other() == null ? null : field(rule.other());
    Predicate<Values> valid;
    switch (rule.kind()) {
      case FIELD -> {
        var compiled = RecordCalculationConstraints.compile(field, rule.constraints());
        valid = values -> {
          try {
            compiled.validate(values.get(rule.field()));
            return true;
          } catch (org.simplepoint.calculation.FormulaException failure) {
            return false;
          }
        };
      }
      case REQUIRED -> valid = values -> !empty(values.get(rule.field()), field.type());
      case RANGE -> {
        requireNumeric(field);
        BigDecimal minimum = decimal(rule.minimum());
        BigDecimal maximum = decimal(rule.maximum());
        valid = values -> inRange(values.get(rule.field()), minimum, maximum);
      }
      case AFTER -> {
        if (!TEMPORAL.contains(field.type()) || field.type() != other.type()) {
          throw invalid("Temporal relation requires fields of the same temporal type", null);
        }
        valid = values -> compare(values.get(rule.field()), values.get(rule.other()), RecordValueConditionOperator.GREATER_THAN);
      }
      case DETAIL_COUNT -> {
        requireScope(rule.detailId());
        BigDecimal minimum = decimal(rule.minimum());
        BigDecimal maximum = decimal(rule.maximum());
        valid = values -> inRange(BigDecimal.valueOf(values.details().get(rule.detailId()).size()), minimum, maximum);
      }
      case DETAIL_SUM -> {
        requireNumeric(field);
        requireNumeric(other);
        valid = values -> sum(values, rule.field(), rule.other());
      }
      default -> throw invalid("Unsupported aggregate validation rule", null);
    }
    return new Check(scope, when, valid);
  }

  private Predicate<Values> condition(final RecordValidationCondition condition, final String scope) {
    var operator = condition.operator();
    if (operator.constant()) {
      return values -> operator == RecordValueConditionOperator.TRUE;
    }
    if (operator.group()) {
      var children = condition.children().stream().map(child -> condition(child, scope)).toList();
      return switch (operator) {
        case AND -> values -> children.stream().allMatch(child -> child.test(values));
        case OR -> values -> children.stream().anyMatch(child -> child.test(values));
        case NOT -> values -> !children.getFirst().test(values);
        default -> throw invalid("Unsupported aggregate condition", null);
      };
    }
    var reference = condition.field();
    if (!"parent".equals(reference.scope()) && !scope.equals(reference.scope())) {
      throw invalid("A row condition cannot reference another detail collection", null);
    }
    var field = field(reference);
    if (operator == RecordValueConditionOperator.IS_EMPTY || operator == RecordValueConditionOperator.IS_NOT_EMPTY) {
      return values -> empty(values.get(reference), field.type()) == (operator == RecordValueConditionOperator.IS_EMPTY);
    }
    if (Set.of(RecordValueConditionOperator.GREATER_THAN, RecordValueConditionOperator.GREATER_THAN_OR_EQUAL,
        RecordValueConditionOperator.LESS_THAN, RecordValueConditionOperator.LESS_THAN_OR_EQUAL).contains(operator)
        && !NUMERIC.contains(field.type()) && !TEMPORAL.contains(field.type())) {
      throw invalid("Ordered conditions require numeric or temporal fields", null);
    }
    var literals = condition.values().stream().map(value -> literal(field, value)).toList();
    return switch (operator) {
      case IN -> values -> literals.stream().anyMatch(value -> compare(values.get(reference), value, RecordValueConditionOperator.EQUALS));
      case NOT_IN -> values -> literals.stream().noneMatch(value -> compare(values.get(reference), value, RecordValueConditionOperator.EQUALS));
      case NOT_EQUALS -> values -> !compare(values.get(reference), literals.getFirst(), RecordValueConditionOperator.EQUALS);
      default -> values -> compare(values.get(reference), literals.getFirst(), operator);
    };
  }

  private static Object literal(final LogicalFieldDefinition field, final LogicalValue value) {
    try {
      var kind = switch (field.type()) {
        case TEXT, STRING -> LogicalValueKind.STRING;
        case INT32, INT64 -> LogicalValueKind.INTEGER;
        default -> LogicalValueKind.valueOf(field.type().name());
      };
      if (kind != value.kind()) {
        throw invalid("Aggregate condition literal type does not match its field", null);
      }
      String text = value.canonicalValue();
      // Thresholds may be outside column length/scale or between stored microseconds.
      // Applying write coercion here would change condition truth values.
      return switch (field.type()) {
        case STRING, TEXT -> text;
        case INT32 -> BigDecimal.valueOf(Integer.parseInt(text));
        case INT64 -> BigDecimal.valueOf(Long.parseLong(text));
        case DECIMAL -> {
          var number = new BigDecimal(text);
          if (number.precision() > 2000 || Math.abs((long) number.scale()) > 1000) {
            throw invalid("Aggregate condition numeric budget exceeded", null);
          }
          yield number;
        }
        case BOOLEAN -> {
          if (!Set.of("true", "false").contains(text)) {
            throw invalid("Aggregate condition boolean is invalid", null);
          }
          yield Boolean.valueOf(text);
        }
        case DATE -> LocalDate.parse(text);
        case TIME -> LocalTime.parse(text);
        case DATETIME -> LocalDateTime.ofInstant(Instant.parse(text), ZoneOffset.UTC);
        case UUID -> UUID.fromString(text).toString();
        case JSON -> LogicalJsonValues.canonicalize(text);
        case BINARY -> throw invalid("Binary aggregate conditions are unsupported", null);
      };
    } catch (DnaRuntimeException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw invalid("Aggregate condition literal is invalid", failure);
    }
  }

  private LogicalFieldDefinition field(final RecordValidationField reference) {
    requireScope(reference.scope());
    var field = fields.get(reference.scope()).get(reference.fieldId());
    if (field == null) {
      throw invalid("Aggregate validation references an unknown or managed field", null);
    }
    return field;
  }

  private void requireScope(final String scope) {
    if (!fields.containsKey(scope)) {
      throw invalid("Aggregate validation references an unknown scope", null);
    }
  }

  private static void requireNumeric(final LogicalFieldDefinition field) {
    if (!NUMERIC.contains(field.type())) {
      throw invalid("Aggregate range or sum requires numeric fields", null);
    }
  }

  private static BigDecimal decimal(final String value) {
    return value == null ? null : new BigDecimal(value);
  }

  private static boolean inRange(final Object value, final BigDecimal minimum, final BigDecimal maximum) {
    return value instanceof BigDecimal number && (minimum == null || number.compareTo(minimum) >= 0)
        && (maximum == null || number.compareTo(maximum) <= 0);
  }

  private static boolean sum(final Values values, final RecordValidationField parent, final RecordValidationField detail) {
    if (!(values.get(parent) instanceof BigDecimal total)) {
      return false;
    }
    BigDecimal sum = BigDecimal.ZERO;
    for (var row : values.details().get(detail.scope())) {
      if (!(row.get(detail.fieldId()) instanceof BigDecimal amount)) {
        return false;
      }
      sum = sum.add(amount);
    }
    return total.compareTo(sum) == 0;
  }

  private static boolean empty(final Object value, final LogicalFieldType type) {
    if (value == null || value instanceof String text && text.codePoints().allMatch(character ->
        Character.isSpaceChar(character) || character >= 9 && character <= 13 || character == 0xFEFF)) {
      return true;
    }
    return type == LogicalFieldType.JSON && LogicalJsonValues.empty((String) value);
  }

  @SuppressWarnings("unchecked")
  private static boolean compare(final Object left, final Object right, final RecordValueConditionOperator operator) {
    if (left == null || right == null) {
      return false;
    }
    int comparison = ((Comparable<Object>) left).compareTo(right);
    return switch (operator) {
      case EQUALS -> comparison == 0;
      case GREATER_THAN -> comparison > 0;
      case GREATER_THAN_OR_EQUAL -> comparison >= 0;
      case LESS_THAN -> comparison < 0;
      case LESS_THAN_OR_EQUAL -> comparison <= 0;
      default -> throw invalid("Unsupported aggregate comparison", null);
    };
  }

  private static DnaRuntimeException invalid(final String message, final Throwable cause) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID, message, cause);
  }

  private record Check(String scope, Predicate<Values> when, Predicate<Values> valid) {
  }

  private record Values(Map<String, Object> parent, Map<String, Object> row, Map<String, List<Map<String, Object>>> details) {
    private Object get(final RecordValidationField field) {
      return ("parent".equals(field.scope()) ? parent : row).get(field.fieldId());
    }
  }
}
