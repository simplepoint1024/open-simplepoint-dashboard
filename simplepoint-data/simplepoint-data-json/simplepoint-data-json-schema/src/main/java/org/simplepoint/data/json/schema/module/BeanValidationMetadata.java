package org.simplepoint.data.json.schema.module;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.MemberScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.groups.Default;
import java.math.BigDecimal;
import java.util.Arrays;

/**
 * Adds portable client constraints without pretending Java-only regex syntax has JavaScript
 * semantics.
 */
final class BeanValidationMetadata {
  // Character.isWhitespace, matching Jakarta NotBlank; JavaScript whitespace membership differs.
  private static final String NON_BLANK =
      "[^\\u0009-\\u000d\\u001c-\\u0020\\u1680\\u2000-\\u2006\\u2008-\\u200a\\u2028-\\u2029\\u205f\\u3000]";

  private BeanValidationMetadata() {}

  static boolean nonNullable(MemberScope<?, ?> field) {
    for (var type :
        java.util.List.of(
            jakarta.validation.constraints.NotNull.class,
            jakarta.validation.constraints.NotBlank.class,
            jakarta.validation.constraints.NotEmpty.class)) {
      var annotation = field.getAnnotationConsideringFieldAndGetterIfSupported(type);
      if (annotation != null
          && defaultGroup(
              (Class<?>[])
                  org.springframework.core.annotation.AnnotationUtils.getValue(
                      annotation, "groups"))) return true;
    }
    return false;
  }

  static void apply(ObjectNode attributes, MemberScope<?, ?> field) {
    customMessages(attributes, field);
    var size =
        field.getAnnotationConsideringFieldAndGetterIfSupported(
            jakarta.validation.constraints.Size.class);
    if (size != null && defaultGroup(size.groups())) {
      Class<?> type = field.getType().getErasedType();
      String suffix =
          CharSequence.class.isAssignableFrom(type)
              ? "Length"
              : java.util.Map.class.isAssignableFrom(type) ? "Properties" : "Items";
      tighten(attributes, "min" + suffix, BigDecimal.valueOf(size.min()), true);
      tighten(attributes, "max" + suffix, BigDecimal.valueOf(size.max()), false);
    }
    var min =
        field.getAnnotationConsideringFieldAndGetterIfSupported(
            jakarta.validation.constraints.Min.class);
    var max =
        field.getAnnotationConsideringFieldAndGetterIfSupported(
            jakarta.validation.constraints.Max.class);
    var decimalMin =
        field.getAnnotationConsideringFieldAndGetterIfSupported(
            jakarta.validation.constraints.DecimalMin.class);
    var decimalMax =
        field.getAnnotationConsideringFieldAndGetterIfSupported(
            jakarta.validation.constraints.DecimalMax.class);
    if (field.getType().getErasedType() != BigDecimal.class) {
      if (min != null && defaultGroup(min.groups()))
        tighten(attributes, "minimum", BigDecimal.valueOf(min.value()), true);
      if (max != null && defaultGroup(max.groups()))
        tighten(attributes, "maximum", BigDecimal.valueOf(max.value()), false);
      if (decimalMin != null && defaultGroup(decimalMin.groups()))
        tighten(
            attributes,
            decimalMin.inclusive() ? "minimum" : "exclusiveMinimum",
            new BigDecimal(decimalMin.value()),
            true);
      if (decimalMax != null && defaultGroup(decimalMax.groups()))
        tighten(
            attributes,
            decimalMax.inclusive() ? "maximum" : "exclusiveMaximum",
            new BigDecimal(decimalMax.value()),
            false);
    }
    var notBlank = field.getAnnotationConsideringFieldAndGetterIfSupported(NotBlank.class);
    if (notBlank != null && defaultGroup(notBlank.groups())) {
      ObjectNode rule = attributes.withArray("allOf").addObject();
      rule.put("pattern", NON_BLANK);
      message(
          rule,
          "pattern",
          notBlank.message().startsWith("i18n:")
              ? notBlank.message()
              : "i18n:form.validation.required");
      message(attributes, "required", notBlank.message());
    }
    var pattern = field.getAnnotationConsideringFieldAndGetterIfSupported(Pattern.class);
    if (pattern != null && defaultGroup(pattern.groups())) {
      if (portable(pattern)) {
        // Jakarta Pattern uses matches(), whereas JSON Schema pattern searches substrings.
        var rule = attributes.withArray("allOf").addObject();
        rule.put("pattern", "^(?:" + pattern.regexp() + ")(?![\\s\\S])");
        message(rule, "pattern", pattern.message());
      } else {
        attributes.withArray("x-server-validation").add("pattern");
      }
    }
  }

  private static void customMessages(ObjectNode attributes, MemberScope<?, ?> field) {
    var constraints =
        java.util.List.<Class<? extends java.lang.annotation.Annotation>>of(
            jakarta.validation.constraints.NotNull.class,
                jakarta.validation.constraints.NotEmpty.class,
            jakarta.validation.constraints.Size.class, jakarta.validation.constraints.Min.class,
            jakarta.validation.constraints.Max.class,
                jakarta.validation.constraints.DecimalMin.class,
            jakarta.validation.constraints.DecimalMax.class,
                jakarta.validation.constraints.Email.class,
            jakarta.validation.constraints.Positive.class,
                jakarta.validation.constraints.PositiveOrZero.class,
            jakarta.validation.constraints.Negative.class,
                jakarta.validation.constraints.NegativeOrZero.class);
    for (var type : constraints) {
      var annotation = field.getAnnotationConsideringFieldAndGetterIfSupported(type);
      if (annotation == null) continue;
      var groups =
          (Class<?>[])
              org.springframework.core.annotation.AnnotationUtils.getValue(annotation, "groups");
      if (groups != null && !defaultGroup(groups)) continue;
      String text =
          (String)
              org.springframework.core.annotation.AnnotationUtils.getValue(annotation, "message");
      var keywords =
          switch (type.getSimpleName()) {
            case "NotNull", "NotEmpty" -> java.util.List.of("required");
            case "Size" ->
                java.util.List.of(
                    "minLength",
                    "maxLength",
                    "minItems",
                    "maxItems",
                    "minProperties",
                    "maxProperties");
            case "Min", "PositiveOrZero" -> java.util.List.of("minimum");
            case "Max", "NegativeOrZero" -> java.util.List.of("maximum");
            case "Positive" -> java.util.List.of("exclusiveMinimum");
            case "Negative" -> java.util.List.of("exclusiveMaximum");
            case "DecimalMin" ->
                java.util.List.of(
                    Boolean.FALSE.equals(
                            org.springframework.core.annotation.AnnotationUtils.getValue(
                                annotation, "inclusive"))
                        ? "exclusiveMinimum"
                        : "minimum");
            case "DecimalMax" ->
                java.util.List.of(
                    Boolean.FALSE.equals(
                            org.springframework.core.annotation.AnnotationUtils.getValue(
                                annotation, "inclusive"))
                        ? "exclusiveMaximum"
                        : "maximum");
            default -> java.util.List.of("format");
          };
      if (text != null) keywords.forEach(keyword -> message(attributes, keyword, text));
    }
  }

  private static void tighten(
      ObjectNode attributes, String keyword, BigDecimal bound, boolean minimum) {
    var existing = attributes.get(keyword);
    if (existing == null
        || !existing.isNumber()
        || (minimum
            ? bound.compareTo(existing.decimalValue()) > 0
            : bound.compareTo(existing.decimalValue()) < 0)) attributes.put(keyword, bound);
  }

  private static boolean portable(Pattern pattern) {
    return pattern.flags().length == 0
        && !java.util.regex.Pattern.compile(
                "\\\\[AQEZzRXGphHvV]|\\(\\?[a-zA-Z-]|\\+\\+|\\*\\+|\\?\\+|}\\+|&&")
            .matcher(pattern.regexp())
            .find();
  }

  private static boolean defaultGroup(Class<?>[] groups) {
    return groups.length == 0 || Arrays.asList(groups).contains(Default.class);
  }

  private static void message(ObjectNode attributes, String keyword, String message) {
    if (message.startsWith("i18n:") && message.length() <= 205)
      attributes.withObject("/x-validation-messages").put(keyword, message.substring(5));
  }
}
