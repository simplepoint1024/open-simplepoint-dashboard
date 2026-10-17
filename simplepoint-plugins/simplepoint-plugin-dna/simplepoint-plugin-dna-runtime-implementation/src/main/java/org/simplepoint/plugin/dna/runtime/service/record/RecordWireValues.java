package org.simplepoint.plugin.dna.runtime.service.record;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalJsonValues;

/** Preserves native JSON and temporal values across the JDBC and Calcite record boundary. */
final class RecordWireValues {

  private static final DateTimeFormatter TIMESTAMP_TEXT =
      new DateTimeFormatterBuilder()
          .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
          .optionalStart()
          .appendOffset("+HH:mm:ss", "Z")
          .optionalEnd()
          .toFormatter();
  private static final Set<String> AUDIT_TIMESTAMPS =
      Set.of("created_at", "updated_at", "deleted_at");

  private RecordWireValues() {}

  static boolean requiresPhysicalProjection(
      final RuntimeRecordModel model, final List<String> fields) {
    return fields.stream()
        .anyMatch(
            field -> {
              LogicalFieldType type = type(model, field);
              return type == LogicalFieldType.DATE
                  || type == LogicalFieldType.TIME
                  || type == LogicalFieldType.DATETIME
                  || type == LogicalFieldType.JSON;
            });
  }

  /**
   * Target connections use UTC; cast at the database before JDBC/Calcite can lose fractional
   * seconds.
   */
  static String projection(
      final RuntimeRecordModel model, final String field, final String column) {
    LogicalFieldType type = type(model, field);
    if (type == LogicalFieldType.DATETIME) {
      return "CAST(CAST(" + column + " AS TIMESTAMP(6)) AS VARCHAR(64))";
    }
    if (type == LogicalFieldType.JSON) {
      return "CAST(" + column + " AS VARCHAR(" + LogicalJsonValues.MAX_STORED_TEXT_LENGTH + "))";
    }
    return type == LogicalFieldType.DATE || type == LogicalFieldType.TIME
        ? "CAST(" + column + " AS VARCHAR(64))"
        : column;
  }

  /**
   * JSON and temporal record values are reusable strings; masking is applied before this
   * conversion.
   */
  static Object wireValue(final RuntimeRecordModel model, final String field, final Object value) {
    if (value == null) {
      return null;
    }
    LogicalFieldType type = type(model, field);
    try {
      // Dynamic JSON maps must never carry exact business numbers as floating point values.
      if (type == LogicalFieldType.DECIMAL) {
        return new BigDecimal(value.toString()).toPlainString();
      }
      if (type == LogicalFieldType.INT64) {
        return Long.toString(new BigDecimal(value.toString()).longValueExact());
      }
      if (type == LogicalFieldType.JSON) {
        return LogicalJsonValues.canonicalizeStored(value.toString());
      }
      if (type == LogicalFieldType.DATE) {
        return LocalDate.parse(value.toString()).toString();
      }
      if (type == LogicalFieldType.TIME) {
        return LocalTime.parse(value.toString()).format(DateTimeFormatter.ISO_LOCAL_TIME);
      }
      if (type == LogicalFieldType.DATETIME) {
        var timestamp =
            TIMESTAMP_TEXT.parseBest(
                value.toString().replace(' ', 'T'), OffsetDateTime::from, LocalDateTime::from);
        return timestamp instanceof OffsetDateTime offset
            ? offset.toInstant().toString()
            : ((LocalDateTime) timestamp).toInstant(ZoneOffset.UTC).toString();
      }
      return value;
    } catch (RuntimeException failure) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED,
          "Invalid stored record representation",
          failure);
    }
  }

  private static LogicalFieldType type(final RuntimeRecordModel model, final String field) {
    var definition = model.requestedField(field);
    return definition == null
        ? AUDIT_TIMESTAMPS.contains(field) ? LogicalFieldType.DATETIME : null
        : definition.type();
  }
}
