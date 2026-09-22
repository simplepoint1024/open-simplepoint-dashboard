package org.simplepoint.core.query;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

/** Strict conversion before binding values; never delegate user input coercion to the database. */
public final class QueryValues {
  private QueryValues() {}

  /** Converts a filter value to the mapped Java type or rejects it before SQL execution. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static Object convert(Class<?> type, String input) {
    String value = input.trim();
    try {
      if (type == String.class) {
        return value;
      }
      if (type == Integer.class || type == int.class) {
        return Integer.valueOf(value);
      }
      if (type == Long.class || type == long.class) {
        return Long.valueOf(value);
      }
      if (type == Short.class || type == short.class) {
        return Short.valueOf(value);
      }
      if (type == Byte.class || type == byte.class) {
        return Byte.valueOf(value);
      }
      if (type == BigDecimal.class) {
        return new BigDecimal(value);
      }
      if (type == BigInteger.class) {
        return new BigInteger(value);
      }
      if (type == Boolean.class || type == boolean.class) {
        if (!value.equals("true") && !value.equals("false")) {
          throw new IllegalArgumentException();
        }
        return Boolean.valueOf(value);
      }
      if (type == Double.class || type == double.class) {
        double number = Double.parseDouble(value);
        if (!Double.isFinite(number)) {
          throw new IllegalArgumentException();
        }
        return number;
      }
      if (type == Float.class || type == float.class) {
        float number = Float.parseFloat(value);
        if (!Float.isFinite(number)) {
          throw new IllegalArgumentException();
        }
        return number;
      }
      if (type == UUID.class) {
        UUID uuid = UUID.fromString(value);
        if (!uuid.toString().equalsIgnoreCase(value)) {
          throw new IllegalArgumentException();
        }
        return uuid;
      }
      if (type.isEnum()) {
        return Enum.valueOf((Class<? extends Enum>) type, value);
      }
      if (type == Character.class || type == char.class) {
        if (value.length() != 1) {
          throw new IllegalArgumentException();
        }
        return value.charAt(0);
      }
      if (type == Instant.class) {
        return instant(value);
      }
      if (type == LocalDate.class) {
        return LocalDate.parse(value);
      }
      if (type == LocalDateTime.class) {
        return LocalDateTime.parse(value);
      }
      if (type == LocalTime.class) {
        return LocalTime.parse(value);
      }
      if (type == OffsetDateTime.class) {
        return OffsetDateTime.parse(value);
      }
      if (type == OffsetTime.class) {
        return OffsetTime.parse(value);
      }
      if (type == ZonedDateTime.class) {
        return ZonedDateTime.parse(value);
      }
      if (type == java.sql.Date.class) {
        return java.sql.Date.valueOf(LocalDate.parse(value));
      }
      if (type == java.sql.Time.class) {
        return java.sql.Time.valueOf(LocalTime.parse(value));
      }
      if (type == java.sql.Timestamp.class) {
        return java.sql.Timestamp.from(instant(value));
      }
      if (type == java.util.Date.class) {
        return java.util.Date.from(instant(value));
      }
    } catch (RuntimeException error) {
      throw new InvalidQueryException("筛选值与字段类型不匹配");
    }
    throw new InvalidQueryException("不支持此字段类型的筛选");
  }

  private static Instant instant(String value) {
    return value.length() == 10
        ? LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC)
        : Instant.parse(value);
  }
}
