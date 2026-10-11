package org.simplepoint.plugin.dna.runtime.api.schema;

import java.util.Objects;
import java.util.regex.Pattern;

/** One application field in a database-neutral target schema. */
public record LogicalFieldDefinition(
    String fieldId,
    String code,
    String displayName,
    LogicalFieldType type,
    boolean nullable,
    Integer length,
    Integer precision,
    Integer scale,
    LogicalValue defaultValue
) {

  private static final Pattern FIELD_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
  private static final Pattern FIELD_CODE = Pattern.compile("[a-z][a-z0-9_]{0,62}");

  /** Validates the logical type modifiers and canonical default value. */
  public LogicalFieldDefinition {
    requireMatch(fieldId, FIELD_ID, "fieldId");
    requireMatch(code, FIELD_CODE, "code");
    if (displayName == null || displayName.isBlank() || displayName.length() > 128) {
      throw new IllegalArgumentException("displayName must contain 1 to 128 characters");
    }
    Objects.requireNonNull(type, "type");
    validateTypeModifiers(type, length, precision, scale);
    validateDefaultValue(type, nullable, defaultValue);
  }

  private static void validateTypeModifiers(
      final LogicalFieldType type,
      final Integer length,
      final Integer precision,
      final Integer scale
  ) {
    if (type == LogicalFieldType.STRING) {
      if (length == null || length < 1 || length > 65_535) {
        throw new IllegalArgumentException("STRING length must be between 1 and 65535");
      }
    } else if (length != null) {
      throw new IllegalArgumentException("length is only supported by STRING fields");
    }
    if (type == LogicalFieldType.DECIMAL) {
      if (precision == null || precision < 1 || precision > 38) {
        throw new IllegalArgumentException("DECIMAL precision must be between 1 and 38");
      }
      if (scale == null || scale < 0 || scale > precision) {
        throw new IllegalArgumentException("DECIMAL scale must be between 0 and precision");
      }
    } else if (precision != null || scale != null) {
      throw new IllegalArgumentException("precision and scale require a DECIMAL field");
    }
  }

  private static void validateDefaultValue(
      final LogicalFieldType type,
      final boolean nullable,
      final LogicalValue defaultValue
  ) {
    if (defaultValue == null) {
      return;
    }
    if (defaultValue.kind() == LogicalValueKind.NULL) {
      if (!nullable) {
        throw new IllegalArgumentException("A required field cannot have a NULL default");
      }
      return;
    }
    LogicalValueKind expected = switch (type) {
      case STRING, TEXT -> LogicalValueKind.STRING;
      case BOOLEAN -> LogicalValueKind.BOOLEAN;
      case INT32, INT64 -> LogicalValueKind.INTEGER;
      case DECIMAL -> LogicalValueKind.DECIMAL;
      case DATE -> LogicalValueKind.DATE;
      case TIME -> LogicalValueKind.TIME;
      case DATETIME -> LogicalValueKind.DATETIME;
      case UUID -> LogicalValueKind.UUID;
      case JSON -> LogicalValueKind.JSON;
      case BINARY -> LogicalValueKind.BINARY;
    };
    if (defaultValue.kind() != expected) {
      throw new IllegalArgumentException(
          "Default value kind " + defaultValue.kind() + " does not match " + type
      );
    }
  }

  private static void requireMatch(
      final String value,
      final Pattern pattern,
      final String name
  ) {
    if (value == null || !pattern.matcher(value).matches()) {
      throw new IllegalArgumentException(name + " has an invalid format");
    }
  }
}
