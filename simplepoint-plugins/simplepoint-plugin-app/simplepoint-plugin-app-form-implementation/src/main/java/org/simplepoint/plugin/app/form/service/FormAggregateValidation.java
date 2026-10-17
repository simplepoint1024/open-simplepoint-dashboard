package org.simplepoint.plugin.app.form.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormValidationConstraint;

/** Pure aggregate checks shared by create, patch and workflow submission preflight. */
final class FormAggregateValidation {
  private static final Set<FormFieldType> NUMERIC = Set.of(
      FormFieldType.DECIMAL, FormFieldType.INT32, FormFieldType.INT64);
  private static final Set<FormFieldType> TEMPORAL = Set.of(
      FormFieldType.DATE, FormFieldType.TIME, FormFieldType.DATETIME);

  private FormAggregateValidation() {}

  static void validateDefinition(ApplicationFormDocument document) {
    if (document.constraints().size() > 100) {
      invalidDefinition("At most 100 aggregate constraints are supported");
    }
    Set<String> ids = new HashSet<>();
    for (var rule : document.constraints()) {
      if (rule.kind() == null || rule.constraintId() == null
          || !rule.constraintId().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
          || !ids.add(rule.constraintId()) || rule.message() == null
          || rule.message().isBlank() || rule.message().length() > 200) {
        invalidDefinition("Invalid aggregate constraint identity or message");
      }
      switch (rule.kind()) {
        case RANGE -> numeric(field(document.fields(), rule.fieldId()));
        case AFTER -> {
          var left = field(document.fields(), rule.fieldId());
          var right = field(document.fields(), rule.otherFieldId());
          if (!TEMPORAL.contains(left.type()) || left.type() != right.type()
              || left.fieldId().equals(right.fieldId())) {
            invalidDefinition("AFTER requires two different fields of the same temporal type");
          }
        }
        case DETAIL_COUNT, DETAIL_RANGE, DETAIL_SUM -> {
          var detail = document.details().stream().filter(d -> d.detailId().equals(rule.detailId()))
              .findFirst().orElseThrow(() -> definition("Constraint references an unknown detail"));
          if (rule.kind() != FormValidationConstraint.Kind.DETAIL_COUNT) {
            numeric(field(detail.fields(), rule.detailFieldId()));
          }
          if (rule.kind() == FormValidationConstraint.Kind.DETAIL_SUM) {
            numeric(field(document.fields(), rule.fieldId()));
          }
        }
        default -> throw definition("Unsupported constraint");
      }
      if (Set.of(FormValidationConstraint.Kind.RANGE, FormValidationConstraint.Kind.DETAIL_COUNT,
          FormValidationConstraint.Kind.DETAIL_RANGE).contains(rule.kind())) {
        if (rule.minimum() == null && rule.maximum() == null) {
          invalidDefinition("Range needs a bound");
        }
        try {
          BigDecimal min = bound(rule.minimum());
          BigDecimal max = bound(rule.maximum());
          if (min != null && max != null && min.compareTo(max) > 0) {
            throw new IllegalArgumentException();
          }
          if (rule.kind() == FormValidationConstraint.Kind.DETAIL_COUNT
              && (min != null && (min.intValueExact() < 0 || min.intValueExact() > 40)
              || max != null && (max.intValueExact() < 0 || max.intValueExact() > 40))) {
            throw new IllegalArgumentException();
          }
        } catch (RuntimeException failure) {
          invalidDefinition("Invalid constraint bounds");
        }
      }
    }
  }

  static void validate(ApplicationFormDocument document, Map<String, Object> values,
      Map<String, List<Map<String, Object>>> details) {
    for (var rule : document.constraints()) {
      boolean valid;
      try {
        valid = switch (rule.kind()) {
          case RANGE -> inRange(decimal(values.get(rule.fieldId())), rule);
          case AFTER -> after(field(document.fields(), rule.fieldId()).type(),
              values.get(rule.fieldId()), values.get(rule.otherFieldId()));
          case DETAIL_COUNT -> inRange(BigDecimal.valueOf(details.get(rule.detailId()).size()), rule);
          case DETAIL_RANGE -> details.get(rule.detailId()).stream()
              .allMatch(row -> inRange(decimal(row.get(rule.detailFieldId())), rule));
          case DETAIL_SUM -> decimal(values.get(rule.fieldId())).compareTo(details.get(rule.detailId()).stream()
              .map(row -> decimal(row.get(rule.detailFieldId()))).reduce(BigDecimal.ZERO, BigDecimal::add)) == 0;
        };
      } catch (IllegalArgumentException | ArithmeticException | java.time.DateTimeException failure) {
        valid = false;
      }
      if (!valid) {
        throw new ApplicationFormException(ApplicationFormErrorCode.RECORD_INVALID, rule.message());
      }
    }
  }

  private static BigDecimal bound(String value) {
    if (value == null) {
      return null;
    }
    if (value.length() > 80 || !value.matches("-?[0-9]+(\\.[0-9]+)?")) {
      throw new IllegalArgumentException();
    }
    return new BigDecimal(value);
  }

  private static BigDecimal decimal(Object value) {
    if (value == null) {
      throw new IllegalArgumentException();
    }
    return new BigDecimal(value.toString());
  }

  private static boolean inRange(BigDecimal value, FormValidationConstraint rule) {
    return (rule.minimum() == null || value.compareTo(bound(rule.minimum())) >= 0)
        && (rule.maximum() == null || value.compareTo(bound(rule.maximum())) <= 0);
  }

  private static boolean after(FormFieldType type, Object left, Object right) {
    if (left == null || right == null) {
      return false;
    }
    return switch (type) {
      case DATE -> LocalDate.parse(left.toString()).isAfter(LocalDate.parse(right.toString()));
      case TIME -> LocalTime.parse(left.toString()).isAfter(LocalTime.parse(right.toString()));
      case DATETIME -> Instant.parse(left.toString()).isAfter(Instant.parse(right.toString()));
      default -> false;
    };
  }

  private static FormFieldDefinition field(List<FormFieldDefinition> fields, String id) {
    return fields.stream().filter(f -> f.fieldId().equals(id)).findFirst()
        .orElseThrow(() -> definition("Constraint references an unknown field"));
  }

  private static void numeric(FormFieldDefinition field) {
    if (!NUMERIC.contains(field.type())) {
      invalidDefinition("Constraint requires a numeric field");
    }
  }

  private static void invalidDefinition(String message) {
    throw definition(message);
  }

  private static ApplicationFormException definition(String message) {
    return new ApplicationFormException(ApplicationFormErrorCode.DOCUMENT_INVALID, message);
  }
}
