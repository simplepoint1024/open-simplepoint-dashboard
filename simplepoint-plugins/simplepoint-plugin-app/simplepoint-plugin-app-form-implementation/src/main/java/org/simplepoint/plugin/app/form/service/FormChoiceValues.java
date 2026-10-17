package org.simplepoint.plugin.app.form.service;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;

/** Shared scalar choice semantics for publication validation, record writes and UI schemas. */
final class FormChoiceValues {

  private FormChoiceValues() {
  }

  /** Persistent values must round-trip through the DNA microsecond contract; predicate thresholds may be finer. */
  static void validateStoredTemporal(final FormFieldDefinition field, final Object value) {
    if (value == null || field.storageMode() == FormStorageMode.TRANSIENT || field.storageMode() == FormStorageMode.COMPUTED) {
      return;
    }
    int nanos = switch (field.type()) {
      case TIME -> LocalTime.parse(canonical(field.type(), value)).getNano();
      case DATETIME -> Instant.parse(canonical(field.type(), value)).getNano();
      case DATE -> {
        canonical(field.type(), value);
        yield 0;
      }
      default -> 0;
    };
    if (nanos % 1000 != 0) {
      throw new IllegalArgumentException("Stored temporal values require microsecond precision");
    }
  }

  /** Canonical identity; numeric values retain all digits and temporal values use ISO forms. */
  static String canonical(final FormFieldType type, final Object value) {
    if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
      throw new IllegalArgumentException("A choice must be a scalar value");
    }
    String text = value.toString();
    if (text.length() > 2000) {
      throw new IllegalArgumentException("Choice value exceeds the scalar budget");
    }
    try {
      return switch (type) {
        case STRING, TEXT -> {
          if (!(value instanceof String)) {
            throw new IllegalArgumentException("A text choice requires a string");
          }
          yield text;
        }
        case BOOLEAN -> {
          if (!"true".equals(text) && !"false".equals(text)) {
            throw new IllegalArgumentException("A boolean choice requires true or false");
          }
          yield text;
        }
        case INT32 -> Integer.toString(new BigDecimal(text).intValueExact());
        case INT64 -> Long.toString(new BigDecimal(text).longValueExact());
        case DECIMAL -> decimal(text);
        case DATE -> LocalDate.parse(text).toString();
        case TIME -> LocalTime.parse(text).toString();
        case DATETIME -> Instant.parse(text).toString();
        case UUID -> UUID.fromString(text).toString();
        case JSON -> throw new IllegalArgumentException("JSON fields cannot declare scalar choices");
      };
    } catch (DateTimeException | ArithmeticException exception) {
      throw new IllegalArgumentException("Choice value does not match the field type", exception);
    }
  }

  private static String decimal(final String value) {
    BigDecimal decimal = new BigDecimal(value);
    if (Math.abs((long) decimal.scale()) > 1000 || decimal.precision() > 2000) {
      throw new IllegalArgumentException("Choice decimal exceeds the scalar budget");
    }
    return decimal.stripTrailingZeros().toPlainString();
  }

  /** Exact wire values for platform selects; long integers and decimals remain strings. */
  static Object wireValue(final FormFieldType type, final String value) {
    String canonical = canonical(type, value);
    return switch (type) {
      case BOOLEAN -> Boolean.valueOf(canonical);
      case INT32 -> Integer.valueOf(canonical);
      case INT64, DECIMAL -> canonical;
      default -> value;
    };
  }

  /** Match stored values to the choice wire representation; preserve masked and retired values. */
  static Object displayValue(final FormFieldDefinition field, final Object value) {
    if (value == null) {
      return value;
    }
    try {
      if (field.choices().isEmpty()) {
        return field.type() == FormFieldType.INT64 || field.type() == FormFieldType.DECIMAL
            ? canonical(field.type(), value) : value;
      }
      String actual = canonical(field.type(), value instanceof java.time.temporal.TemporalAccessor
          ? value.toString() : value);
      for (var choice : field.choices()) {
        if (actual.equals(canonical(field.type(), choice.value()))) {
          return wireValue(field.type(), choice.value());
        }
      }
    } catch (IllegalArgumentException exception) {
      // Masked and legacy values must remain readable without manufacturing a valid choice.
    }
    return value;
  }
}
