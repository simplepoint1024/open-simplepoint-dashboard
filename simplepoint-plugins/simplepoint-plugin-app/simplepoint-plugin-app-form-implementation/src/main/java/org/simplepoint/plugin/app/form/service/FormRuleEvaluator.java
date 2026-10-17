package org.simplepoint.plugin.app.form.service;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntPredicate;
import org.simplepoint.plugin.app.form.api.model.FormCondition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalJsonValues;

/** Evaluates bounded declarative predicates with the same typed semantics as FormRuntime. */
final class FormRuleEvaluator {

  private FormRuleEvaluator() {
  }

  static boolean matches(final FormCondition condition, final Map<String, Object> values) {
    return matches(condition, values, Map.of());
  }

  static boolean matches(final FormCondition condition, final Map<String, Object> values, final Map<String, FormFieldType> types) {
    Object actual = condition.fieldId() == null ? null : values.get(condition.fieldId());
    return switch (condition.operator()) {
      case AND -> condition.children().stream().allMatch(child -> matches(child, values, types));
      case OR -> condition.children().stream().anyMatch(child -> matches(child, values, types));
      case NOT -> !matches(condition.children().getFirst(), values, types);
      case IS_EMPTY -> empty(actual, types.get(condition.fieldId()));
      case IS_NOT_EMPTY -> !empty(actual, types.get(condition.fieldId()));
      case EQUALS -> compare(actual, condition.values().getFirst(), result -> result == 0);
      case NOT_EQUALS -> !compare(actual, condition.values().getFirst(), result -> result == 0);
      case IN -> condition.values().stream().anyMatch(value -> compare(actual, value, result -> result == 0));
      case NOT_IN -> condition.values().stream().noneMatch(value -> compare(actual, value, result -> result == 0));
      case GREATER_THAN -> compare(actual, condition.values().getFirst(), result -> result > 0);
      case GREATER_THAN_OR_EQUAL -> compare(actual, condition.values().getFirst(), result -> result >= 0);
      case LESS_THAN -> compare(actual, condition.values().getFirst(), result -> result < 0);
      case LESS_THAN_OR_EQUAL -> compare(actual, condition.values().getFirst(), result -> result <= 0);
    };
  }

  private static boolean compare(final Object actual, final FormValue expected, final IntPredicate predicate) {
    if (actual == null || expected == null || expected.kind() == FormValueKind.NULL) {
      return false;
    }
    String value = expected.value();
    try {
      Integer comparison = switch (expected.kind()) {
        case INTEGER, DECIMAL -> decimal(actual).compareTo(decimal(value));
        case DATE -> LocalDate.parse(actual.toString()).compareTo(LocalDate.parse(value));
        case TIME -> LocalTime.parse(actual.toString()).compareTo(LocalTime.parse(value));
        case DATETIME -> Instant.parse(actual.toString()).compareTo(Instant.parse(value));
        case UUID -> UUID.fromString(actual.toString()).toString().compareTo(UUID.fromString(value).toString());
        case BOOLEAN -> actual instanceof Boolean bool ? Boolean.compare(bool, Boolean.parseBoolean(value)) : null;
        case STRING -> actual instanceof String text ? text.compareTo(value) : null;
        case JSON -> LogicalJsonValues.canonicalize(actual).compareTo(LogicalJsonValues.canonicalize(value));
        default -> null;
      };
      return comparison != null && predicate.test(comparison);
    } catch (IllegalArgumentException | ArithmeticException | DateTimeException exception) {
      return false;
    }
  }

  private static BigDecimal decimal(final Object value) {
    return new BigDecimal(FormChoiceValues.canonical(FormFieldType.DECIMAL, value));
  }

  static boolean empty(final Object value, final FormFieldType type) {
    if (type != FormFieldType.JSON || empty(value)) {
      return empty(value);
    }
    try {
      return LogicalJsonValues.empty(LogicalJsonValues.canonicalize(value));
    } catch (IllegalArgumentException failure) {
      return false;
    }
  }

  static boolean empty(final Object value) {
    return value == null || value instanceof String text && text.codePoints().allMatch(character ->
        Character.isSpaceChar(character) || character >= 9 && character <= 13 || character == 0xFEFF)
        || value instanceof java.util.Collection<?> collection && collection.isEmpty()
        || value instanceof Map<?, ?> map && map.isEmpty();
  }
}
