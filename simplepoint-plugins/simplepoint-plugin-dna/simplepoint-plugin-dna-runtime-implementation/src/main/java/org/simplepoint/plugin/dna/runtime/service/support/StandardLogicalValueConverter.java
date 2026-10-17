package org.simplepoint.plugin.dna.runtime.service.support;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.UUID;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalJsonValues;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueConverter;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;
import org.springframework.stereotype.Component;

/** Strict database-neutral value conversion with no silent lossy coercion. */
@Component
public class StandardLogicalValueConverter implements LogicalValueConverter {

  @Override
  public LogicalValue normalize(
      final LogicalFieldDefinition field,
      final Object rawValue
  ) {
    if (field == null) {
      throw conversionFailure("unknown", null);
    }
    if (rawValue == null) {
      if (!field.nullable()) {
        throw conversionFailure(field.code(), field.type());
      }
      return LogicalValue.nullValue();
    }
    try {
      return switch (field.type()) {
        case STRING -> normalizeString(field, rawValue);
        case TEXT -> value(LogicalValueKind.STRING, requireString(rawValue));
        case BOOLEAN -> normalizeBoolean(rawValue);
        case INT32 -> normalizeInteger(rawValue, 32);
        case INT64 -> normalizeInteger(rawValue, 64);
        case DECIMAL -> normalizeDecimal(field, rawValue);
        case DATE -> value(LogicalValueKind.DATE, normalizeDate(rawValue));
        case TIME -> value(LogicalValueKind.TIME, normalizeTime(rawValue));
        case DATETIME -> value(LogicalValueKind.DATETIME, normalizeDateTime(rawValue));
        case UUID -> value(LogicalValueKind.UUID, normalizeUuid(rawValue));
        case JSON -> value(LogicalValueKind.JSON, normalizeJson(rawValue));
        case BINARY -> value(LogicalValueKind.BINARY, normalizeBinary(rawValue));
      };
    } catch (DnaRuntimeException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw conversionFailure(field.code(), field.type(), exception);
    }
  }

  private static LogicalValue normalizeString(
      final LogicalFieldDefinition field,
      final Object rawValue
  ) {
    String text = requireString(rawValue);
    if (text.codePointCount(0, text.length()) > field.length()) {
      throw conversionFailure(field.code(), field.type());
    }
    return value(LogicalValueKind.STRING, text);
  }

  private static LogicalValue normalizeBoolean(final Object rawValue) {
    if (!(rawValue instanceof Boolean bool)) {
      throw new IllegalArgumentException("Boolean input required");
    }
    return value(LogicalValueKind.BOOLEAN, Boolean.toString(bool));
  }

  private static LogicalValue normalizeInteger(final Object rawValue, final int bits) {
    BigInteger integer = decimal(rawValue).toBigIntegerExact();
    if (bits == 32
        && (integer.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0
        || integer.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0)) {
      throw new ArithmeticException("INT32 overflow");
    }
    if (bits == 64
        && (integer.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0
        || integer.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0)) {
      throw new ArithmeticException("INT64 overflow");
    }
    return value(LogicalValueKind.INTEGER, integer.toString());
  }

  private static LogicalValue normalizeDecimal(
      final LogicalFieldDefinition field,
      final Object rawValue
  ) {
    BigDecimal decimal = decimal(rawValue);
    if (decimal.scale() > field.scale()) {
      throw new ArithmeticException("Decimal scale overflow");
    }
    BigDecimal normalized = decimal.setScale(field.scale());
    if (normalized.precision() > field.precision()) {
      throw new ArithmeticException("Decimal precision overflow");
    }
    return value(LogicalValueKind.DECIMAL, normalized.toPlainString());
  }

  private static String normalizeDate(final Object rawValue) {
    if (rawValue instanceof LocalDate date) {
      return date.toString();
    }
    return LocalDate.parse(requireString(rawValue)).toString();
  }

  private static String normalizeTime(final Object rawValue) {
    LocalTime time = rawValue instanceof LocalTime value ? value : LocalTime.parse(requireString(rawValue));
    requireMicroseconds(time.getNano());
    return time.toString();
  }

  private static String normalizeDateTime(final Object rawValue) {
    Instant instant;
    if (rawValue instanceof Instant value) {
      instant = value;
    } else if (rawValue instanceof OffsetDateTime value) {
      instant = value.toInstant();
    } else if (rawValue instanceof ZonedDateTime value) {
      instant = value.toInstant();
    } else {
      String text = requireString(rawValue);
      try {
        instant = Instant.parse(text);
      } catch (RuntimeException ignored) {
        instant = OffsetDateTime.parse(text).toInstant();
      }
    }
    requireMicroseconds(instant.getNano());
    return instant.toString();
  }

  private static void requireMicroseconds(final int nanos) {
    if (nanos % 1000 != 0) {
      throw new IllegalArgumentException("Stored temporal values require microsecond precision");
    }
  }

  private static String normalizeUuid(final Object rawValue) {
    UUID uuid = rawValue instanceof UUID value
        ? value : UUID.fromString(requireString(rawValue));
    return uuid.toString();
  }

  private static String normalizeJson(final Object rawValue) {
    return LogicalJsonValues.canonicalize(rawValue);
  }

  private static String normalizeBinary(final Object rawValue) {
    byte[] bytes;
    if (rawValue instanceof byte[] value) {
      bytes = value;
    } else {
      bytes = Base64.getDecoder().decode(requireString(rawValue));
    }
    return Base64.getEncoder().withoutPadding().encodeToString(bytes);
  }

  private static BigDecimal decimal(final Object rawValue) {
    if (rawValue instanceof Number || rawValue instanceof String) {
      return new BigDecimal(rawValue.toString());
    }
    throw new IllegalArgumentException("Numeric input required");
  }

  private static String requireString(final Object rawValue) {
    if (rawValue instanceof String value) {
      return value;
    }
    throw new IllegalArgumentException("String input required");
  }

  private static LogicalValue value(
      final LogicalValueKind kind,
      final String canonicalValue
  ) {
    return new LogicalValue(kind, canonicalValue);
  }

  private static DnaRuntimeException conversionFailure(
      final String fieldCode,
      final LogicalFieldType fieldType
  ) {
    return conversionFailure(fieldCode, fieldType, null);
  }

  private static DnaRuntimeException conversionFailure(
      final String fieldCode,
      final LogicalFieldType fieldType,
      final Throwable cause
  ) {
    String message = "Value for field " + fieldCode + " is invalid for " + fieldType;
    return cause == null
        ? new DnaRuntimeException(DnaRuntimeErrorCode.VALUE_CONVERSION_FAILED, message)
        : new DnaRuntimeException(
            DnaRuntimeErrorCode.VALUE_CONVERSION_FAILED,
            message,
            cause
        );
  }
}
