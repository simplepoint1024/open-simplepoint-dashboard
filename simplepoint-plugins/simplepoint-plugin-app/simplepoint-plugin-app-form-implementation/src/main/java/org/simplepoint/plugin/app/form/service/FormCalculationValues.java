package org.simplepoint.plugin.app.form.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.simplepoint.calculation.FormulaException;
import org.simplepoint.calculation.FormulaValues;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalJsonValues;

/** Exact field-type boundary; dependent expressions observe the same value that can be stored. */
final class FormCalculationValues {
  private FormCalculationValues() {}

  static Object typed(FormFieldDefinition field, Object value) {
    if (value == null) {
      if (field.storageRequired()) {
        throw new FormulaException("RESULT_REQUIRED");
      }
      return null;
    }
    try {
      Object result =
          switch (field.type()) {
            case STRING, TEXT -> {
              if (!(value instanceof String text)) {
                throw new FormulaException("RESULT_TYPE");
              }
              if (field.length() != null
                  && text.codePointCount(0, text.length()) > field.length()) {
                throw new FormulaException("RESULT_RANGE");
              }
              yield text;
            }
            case BOOLEAN -> {
              if (!(value instanceof Boolean)) {
                throw new FormulaException("RESULT_TYPE");
              }
              yield value;
            }
            case INT32 -> BigDecimal.valueOf(number(value).intValueExact());
            case INT64 -> BigDecimal.valueOf(number(value).longValueExact());
            case DECIMAL -> {
              // Designers use ROUND explicitly. Silent per-column rounding would make totals
              // disagree.
              var decimal = number(value).setScale(field.scale(), RoundingMode.UNNECESSARY);
              if (decimal.precision() > field.precision()) {
                throw new FormulaException("RESULT_RANGE");
              }
              yield decimal;
            }
            case DATE -> value instanceof LocalDate date ? date : LocalDate.parse(value.toString());
            case TIME -> {
              var time =
                  value instanceof LocalTime local ? local : LocalTime.parse(value.toString());
              requireMicroseconds(time.getNano());
              yield time;
            }
            case DATETIME -> {
              var time =
                  value instanceof LocalDateTime local
                      ? local
                      : LocalDateTime.ofInstant(Instant.parse(value.toString()), ZoneOffset.UTC);
              requireMicroseconds(time.getNano());
              yield time;
            }
            case UUID -> UUID.fromString(value.toString()).toString();
            case JSON -> LogicalJsonValues.canonicalize(value);
          };
      FormFieldValidations.value(field, result);
      if (!field.choices().isEmpty()) {
        String canonical = FormChoiceValues.canonical(field.type(), wire(field, result));
        if (field.choices().stream()
            .noneMatch(
                choice ->
                    FormChoiceValues.canonical(field.type(), choice.value()).equals(canonical))) {
          throw new FormulaException("RESULT_CHOICE");
        }
      }
      return result;
    } catch (FormulaException error) {
      throw error;
    } catch (ArithmeticException error) {
      throw new FormulaException("RESULT_RANGE");
    } catch (IllegalArgumentException | java.time.DateTimeException error) {
      throw new FormulaException("RESULT_TYPE");
    }
  }

  /** Keeps the existing precision-safe form wire contract without changing evaluation semantics. */
  static Object wire(FormFieldDefinition field, Object value) {
    if (value == null) {
      return null;
    }
    return switch (field.type()) {
      case INT32 -> ((BigDecimal) value).intValueExact();
      case INT64, DECIMAL -> ((BigDecimal) value).toPlainString();
      case DATE, TIME -> value.toString();
      case DATETIME -> ((LocalDateTime) value).toInstant(ZoneOffset.UTC).toString();
      default -> value;
    };
  }

  private static BigDecimal number(Object value) {
    if (!(value instanceof Number || value instanceof String)
        || value instanceof Double
        || value instanceof Float) {
      throw new FormulaException("RESULT_TYPE");
    }
    return FormulaValues.number(value);
  }

  private static void requireMicroseconds(int nanos) {
    if (nanos % 1000 != 0) {
      throw new FormulaException("RESULT_RANGE");
    }
  }
}
