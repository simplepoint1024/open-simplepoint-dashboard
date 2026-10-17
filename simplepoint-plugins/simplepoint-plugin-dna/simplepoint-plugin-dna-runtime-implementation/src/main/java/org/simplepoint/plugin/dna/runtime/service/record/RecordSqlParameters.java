package org.simplepoint.plugin.dna.runtime.service.record;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.simplepoint.plugin.dna.runtime.service.schema.RuntimeSchemaDialect;

/** Shared Calcite parameter encoding for record and journal SQL. */
final class RecordSqlParameters {
  private final List<Object> values = new ArrayList<>();

  String add(final Object value) {
    if (value instanceof RecordJsonParameter json) {
      values.add(json);
      return "(CAST(? AS VARCHAR(" + Math.max(1, json.canonical().length()) + ")) FORMAT JSON)";
    }
    if (value instanceof Timestamp timestamp) {
      Instant instant = timestamp.toInstant().truncatedTo(ChronoUnit.MICROS);
      values.add(LocalDateTime.ofInstant(instant, ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
      return "CAST(? AS TIMESTAMP(6))";
    }
    if (value instanceof Date) {
      values.add(value.toString());
      return "CAST(? AS DATE)";
    }
    if (value instanceof LocalTime time) {
      values.add(time.format(DateTimeFormatter.ISO_LOCAL_TIME));
      return "CAST(? AS TIME(6))";
    }
    if (value instanceof Time) {
      values.add(value.toString());
      return "CAST(? AS TIME(6))";
    }
    values.add(value);
    return "?";
  }

  static List<Object> bind(final List<Object> source,
      final RuntimeSchemaDialect dialect) {
    return source.stream().map(value -> {
      if (value instanceof RecordJsonParameter json) {
        dialect.validateRecordJson(json.canonical());
        return (Object) json.canonical();
      }
      return value;
    }).toList();
  }

  List<Object> values() {
    return Collections.unmodifiableList(new ArrayList<>(values));
  }
}
