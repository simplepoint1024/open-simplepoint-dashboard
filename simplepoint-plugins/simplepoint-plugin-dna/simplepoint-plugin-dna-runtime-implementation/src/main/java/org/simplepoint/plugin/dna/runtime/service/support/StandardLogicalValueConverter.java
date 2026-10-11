package org.simplepoint.plugin.dna.runtime.service.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.UUID;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueConverter;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;
import org.springframework.stereotype.Component;

/** Strict database-neutral value conversion with no silent lossy coercion. */
@Component
public class StandardLogicalValueConverter implements LogicalValueConverter {

  private final ObjectMapper objectMapper;

  /** Creates a strict converter with an isolated JSON mapper. */
  public StandardLogicalValueConverter(final ObjectMapper objectMapper) {
    this.objectMapper = objectMapper.copy();
  }

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
    } catch (RuntimeException | JsonProcessingException exception) {
      throw conversionFailure(field.code(), field.type(), exception);
    }
  }

  private static LogicalValue normalizeString(
      final LogicalFieldDefinition field,
      final Object rawValue
  ) {
    String text = requireString(rawValue);
    if (text.length() > field.length()) {
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
    if (rawValue instanceof LocalTime time) {
      return time.toString();
    }
    return LocalTime.parse(requireString(rawValue)).toString();
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
    return instant.toString();
  }

  private static String normalizeUuid(final Object rawValue) {
    UUID uuid = rawValue instanceof UUID value
        ? value : UUID.fromString(requireString(rawValue));
    return uuid.toString();
  }

  private String normalizeJson(final Object rawValue) throws JsonProcessingException {
    JsonNode tree = rawValue instanceof String value
        ? objectMapper.readTree(value) : objectMapper.valueToTree(rawValue);
    if (tree == null || tree.isMissingNode()) {
      throw new IllegalArgumentException("JSON value is missing");
    }
    return objectMapper.writeValueAsString(sortJson(tree));
  }

  private JsonNode sortJson(final JsonNode source) {
    if (source.isObject()) {
      ObjectNode result = objectMapper.createObjectNode();
      source.properties().stream()
          .sorted(Comparator.comparing(java.util.Map.Entry::getKey))
          .forEach(entry -> result.set(entry.getKey(), sortJson(entry.getValue())));
      return result;
    }
    if (source.isArray()) {
      ArrayNode result = objectMapper.createArrayNode();
      source.forEach(child -> result.add(sortJson(child)));
      return result;
    }
    if (source.isNumber()) {
      BigDecimal number = source.decimalValue().stripTrailingZeros();
      if (number.scale() < 0) {
        number = number.setScale(0);
      }
      return DecimalNode.valueOf(number);
    }
    return source;
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
