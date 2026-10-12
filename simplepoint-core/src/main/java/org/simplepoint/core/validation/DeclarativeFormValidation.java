package org.simplepoint.core.validation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.simplepoint.api.schema.SchemaConstraints;

/**
 * Validates writable input after tenant/audit/field-policy merging and before persistence or audit
 * writes.
 */
public final class DeclarativeFormValidation {
  private record Rule(
      Field field, boolean required, Map<String, Object> keywords, Pattern pattern) {}

  private static final ClassValue<List<Rule>> RULES =
      new ClassValue<>() {
        @Override
        protected List<Rule> computeValue(Class<?> type) {
          List<Rule> rules = new ArrayList<>();
          Set<String> seen = new java.util.HashSet<>();
          for (Class<?> current = type;
              current != null && current != Object.class;
              current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
              if (Modifier.isStatic(field.getModifiers())
                  || field.isSynthetic()
                  || !seen.add(field.getName())) continue;
              Schema schema = field.getAnnotation(Schema.class);
              if (schema == null
                  || schema.hidden()
                  || schema.accessMode() == Schema.AccessMode.READ_ONLY) continue;
              var keywords = SchemaConstraints.keywords(schema, field.getType());
              var column = field.getAnnotation(jakarta.persistence.Column.class);
              boolean required =
                  SchemaConstraints.required(
                      schema, field.getType(), column != null && !column.nullable());
              if (!required && keywords.isEmpty()) continue;
              if (!field.trySetAccessible())
                throw new IllegalStateException("Cannot access declarative form field");
              Pattern pattern =
                  keywords.get("pattern") instanceof String expression
                      ? Pattern.compile(expression)
                      : null;
              rules.add(new Rule(field, required, Map.copyOf(keywords), pattern));
            }
          }
          return List.copyOf(rules);
        }
      };

  private DeclarativeFormValidation() {}

  public static void validate(Object value, Set<String> protectedFields, Validator validator) {
    if (value == null) return;
    List<FormViolation> violations = new ArrayList<>();
    for (Rule rule : RULES.get(value.getClass())) {
      String field = rule.field().getName();
      if (protectedFields.contains(field)) continue;
      Object input;
      try {
        input = rule.field().get(value);
      } catch (IllegalAccessException error) {
        throw new IllegalStateException("Cannot read declarative form field", error);
      }
      if (input == null) {
        if (rule.required()) violations.add(new FormViolation(field, "required", Map.of()));
        continue;
      }
      if (input instanceof CharSequence text) {
        int length = Character.codePointCount(text, 0, text.length());
        bound(violations, field, "minLength", length, rule.keywords().get("minLength"), false);
        bound(violations, field, "maxLength", length, rule.keywords().get("maxLength"), true);
        if (rule.pattern() != null && !rule.pattern().matcher(text).find())
          violations.add(new FormViolation(field, "pattern", Map.of()));
      }
      if (input instanceof Number number) {
        BigDecimal decimal;
        try {
          decimal = new BigDecimal(number.toString());
        } catch (NumberFormatException error) {
          violations.add(new FormViolation(field, "type", Map.of("type", "number")));
          continue;
        }
        for (String keyword :
            List.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum")) {
          if (!(rule.keywords().get(keyword) instanceof BigDecimal limit)) continue;
          int comparison = decimal.compareTo(limit);
          boolean invalid =
              switch (keyword) {
                case "minimum" -> comparison < 0;
                case "maximum" -> comparison > 0;
                case "exclusiveMinimum" -> comparison <= 0;
                default -> comparison >= 0;
              };
          if (invalid)
            violations.add(
                new FormViolation(field, keyword, Map.of("limit", limit.toPlainString())));
        }
        if (rule.keywords().get("multipleOf") instanceof BigDecimal multiple
            && decimal.remainder(multiple).signum() != 0)
          violations.add(
              new FormViolation(
                  field, "multipleOf", Map.of("multipleOf", multiple.toPlainString())));
      }
      if (rule.keywords().get("enum") instanceof List<?> allowed && !allowedValue(allowed, input))
        violations.add(new FormViolation(field, "enum", Map.of()));
      if (rule.keywords().get("items") instanceof Map<?, ?> items
          && items.get("enum") instanceof List<?> allowed) {
        var values =
            input instanceof java.util.Collection<?> collection
                ? collection.stream()
                : input.getClass().isArray()
                    ? java.util.stream.IntStream.range(0, java.lang.reflect.Array.getLength(input))
                        .mapToObj(index -> java.lang.reflect.Array.get(input, index))
                    : java.util.stream.Stream.of(input);
        if (values.anyMatch(item -> !allowedValue(allowed, item)))
          violations.add(new FormViolation(field, "enum", Map.of()));
      }
    }
    if (validator != null)
      validator.validate(value).stream()
          .filter(
              item ->
                  !protectedFields.contains(
                      item.getPropertyPath().toString().split("[.\\[]", 2)[0]))
          .map(DeclarativeFormValidation::fromConstraint)
          .forEach(violations::add);
    if (!violations.isEmpty())
      throw new InvalidFormException(
          violations.stream()
              .distinct()
              .sorted(
                  java.util.Comparator.comparing(FormViolation::field)
                      .thenComparing(FormViolation::keyword))
              .limit(100)
              .toList());
  }

  private static boolean allowedValue(List<?> allowed, Object value) {
    if (value instanceof Number number)
      return allowed.stream()
          .anyMatch(
              item ->
                  item instanceof BigDecimal decimal
                      && decimal.compareTo(new BigDecimal(number.toString())) == 0);
    return allowed.contains(value instanceof Enum<?> item ? item.name() : value);
  }

  private static void bound(
      List<FormViolation> violations,
      String field,
      String keyword,
      int value,
      Object limit,
      boolean maximum) {
    if (limit instanceof Integer number && (maximum ? value > number : value < number))
      violations.add(new FormViolation(field, keyword, Map.of("limit", number)));
  }

  public static FormViolation fromConstraint(ConstraintViolation<?> violation) {
    String annotation =
        violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
    var attributes = violation.getConstraintDescriptor().getAttributes();
    String keyword =
        switch (annotation) {
          case "NotNull", "NotBlank", "NotEmpty" -> "required";
          case "Pattern" -> "pattern";
          case "Email" -> "format";
          case "Min", "DecimalMin", "PositiveOrZero" -> "minimum";
          case "Max", "DecimalMax", "NegativeOrZero" -> "maximum";
          case "Positive" -> "exclusiveMinimum";
          case "Negative" -> "exclusiveMaximum";
          case "Size" -> "size";
          default -> "invalid";
        };
    if ((annotation.equals("DecimalMin") || annotation.equals("DecimalMax"))
        && Boolean.FALSE.equals(attributes.get("inclusive")))
      keyword = annotation.equals("DecimalMin") ? "exclusiveMinimum" : "exclusiveMaximum";
    Map<String, Object> params = new java.util.LinkedHashMap<>();
    if (annotation.equals("Email")) params.put("format", "email");
    if (keyword.contains("imum"))
      params.put("limit", attributes.getOrDefault("value", 0).toString());
    if (keyword.equals("size")) {
      params.put("min", attributes.get("min"));
      params.put("max", attributes.get("max"));
    }
    String template = violation.getMessageTemplate();
    String key = template.startsWith("i18n:") ? template.substring(5) : null;
    return new FormViolation(
        violation.getPropertyPath().toString(), keyword, Map.copyOf(params), key);
  }
}
