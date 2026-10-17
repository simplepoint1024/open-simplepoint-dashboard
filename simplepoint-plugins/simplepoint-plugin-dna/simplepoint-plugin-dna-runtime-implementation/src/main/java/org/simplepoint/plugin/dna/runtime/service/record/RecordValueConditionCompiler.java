package org.simplepoint.plugin.dna.runtime.service.record;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordJsonSemantics;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueCondition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueConditionOperator;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalJsonValues;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;

/** Compiles typed management conditions to portable, parameter-bound Calcite predicates. */
final class RecordValueConditionCompiler {

  private RecordValueConditionCompiler() {
  }

  static String compile(final RuntimeRecordModel model, final RecordAccessContext access,
      final RecordValueCondition condition, final RecordSqlParameters parameters, final RecordJsonSemantics semantics) {
    if (condition.operator().constant()) {
      return condition.operator() == RecordValueConditionOperator.TRUE ? "(1 = 1)" : "(1 = 0)";
    }
    if (condition.operator().group()) {
      List<String> children = condition.children().stream().map(child -> compile(model, access, child, parameters, semantics)).toList();
      return switch (condition.operator()) {
        case AND -> "(" + String.join(" AND ", children) + ")";
        case OR -> "(" + String.join(" OR ", children) + ")";
        case NOT -> "(NOT " + children.getFirst() + ")";
        default -> throw invalid();
      };
    }
    LogicalFieldDefinition field = model.requestedField(condition.field());
    if (field == null || !access.readableFields().contains(field.code()) || access.maskedFields().contains(field.code())) {
      throw invalid();
    }
    String column = "\"" + model.activeCode(field.code()) + "\"";
    if (condition.operator() == RecordValueConditionOperator.IS_EMPTY || condition.operator() == RecordValueConditionOperator.IS_NOT_EMPTY) {
      String empty = RecordCompletenessPredicate.compile(column, field.type(), parameters, semantics);
      return condition.operator() == RecordValueConditionOperator.IS_EMPTY ? "(" + empty + ")" : "(NOT (" + empty + "))";
    }
    try {
      return comparison(column, model.activeField(field.code()), condition, parameters);
    } catch (IllegalArgumentException | java.time.DateTimeException failure) {
      // Never expose an expected business value in the public error.
      throw invalid();
    }
  }

  private static String comparison(final String column, final LogicalFieldDefinition field,
      final RecordValueCondition condition, final RecordSqlParameters parameters) {
    var operator = condition.operator();
    boolean negative = operator == RecordValueConditionOperator.NOT_EQUALS || operator == RecordValueConditionOperator.NOT_IN;
    String symbol = switch (operator) {
      case EQUALS, NOT_EQUALS, IN, NOT_IN -> "=";
      case GREATER_THAN -> ">";
      case GREATER_THAN_OR_EQUAL -> ">=";
      case LESS_THAN -> "<";
      case LESS_THAN_OR_EQUAL -> "<=";
      default -> throw invalid();
    };
    List<String> equalities = condition.values().stream()
        .map(value -> scalar(column, field, symbol, value, parameters)).toList();
    // SQL NULL never escapes a scalar condition, including through nested NOT/AND/OR.
    String positive = "(" + column + " IS NOT NULL AND (" + String.join(" OR ", equalities) + "))";
    return negative ? "(NOT " + positive + ")" : positive;
  }

  private static String scalar(final String column, final LogicalFieldDefinition field, final String symbol,
      final LogicalValue value, final RecordSqlParameters parameters) {
    return switch (field.type()) {
      case STRING, TEXT -> {
        kind(value, LogicalValueKind.STRING);
        equalityOnly(symbol);
        yield textEquality(column, value.canonicalValue(), parameters);
      }
      case UUID -> {
        kind(value, LogicalValueKind.UUID);
        equalityOnly(symbol);
        String canonical = UUID.fromString(value.canonicalValue()).toString();
        yield textEquality("LOWER(CAST(" + column + " AS VARCHAR(36)))", canonical, parameters);
      }
      case BOOLEAN -> {
        kind(value, LogicalValueKind.BOOLEAN);
        equalityOnly(symbol);
        if (!List.of("true", "false").contains(value.canonicalValue())) {
          throw invalid();
        }
        yield column + " = " + parameters.add(Boolean.valueOf(value.canonicalValue()));
      }
      case INT32, INT64 -> {
        kind(value, LogicalValueKind.INTEGER);
        Object number = field.type() == LogicalFieldType.INT32
            ? (Object) Integer.valueOf(value.canonicalValue()) : Long.valueOf(value.canonicalValue());
        yield column + " " + symbol + " " + parameters.add(number);
      }
      case DECIMAL -> decimal(column, field, symbol, value, parameters);
      case DATE -> {
        kind(value, LogicalValueKind.DATE);
        String date = LocalDate.parse(value.canonicalValue()).toString();
        yield column + " " + symbol + " CAST(" + textParameter(date, parameters) + " AS DATE)";
      }
      case TIME, DATETIME -> temporal(column, field.type(), symbol, value, parameters);
      case JSON -> {
        kind(value, LogicalValueKind.JSON);
        equalityOnly(symbol);
        yield column + " = " + parameters.add(new RecordJsonParameter(LogicalJsonValues.canonicalize(value.canonicalValue())));
      }
      case BINARY -> throw invalid();
    };
  }

  private static String decimal(final String column, final LogicalFieldDefinition field, final String symbol,
      final LogicalValue value, final RecordSqlParameters parameters) {
    kind(value, LogicalValueKind.DECIMAL);
    BigDecimal number = new BigDecimal(value.canonicalValue());
    if (number.precision() > 2000 || Math.abs((long) number.scale()) > 1000) {
      throw invalid();
    }
    // Use ACTIVE storage bounds: a historical schema may have a narrower scale or precision.
    BigDecimal maximum = BigDecimal.TEN.pow(field.precision()).subtract(BigDecimal.ONE).movePointLeft(field.scale());
    if (number.compareTo(maximum) > 0) {
      return "<".equals(symbol) || "<=".equals(symbol) ? "1 = 1" : "1 = 0";
    }
    if (number.compareTo(maximum.negate()) < 0) {
      return ">".equals(symbol) || ">=".equals(symbol) ? "1 = 1" : "1 = 0";
    }
    BigDecimal floor = number.setScale(field.scale(), RoundingMode.FLOOR);
    boolean aligned = number.compareTo(floor) == 0;
    if (!aligned && "=".equals(symbol)) {
      return "1 = 0";
    }
    String comparison = !aligned && "<".equals(symbol) ? "<=" : !aligned && ">=".equals(symbol) ? ">" : symbol;
    return column + " " + comparison + " " + parameters.add(floor);
  }

  private static String temporal(final String column, final LogicalFieldType type, final String symbol,
      final LogicalValue value, final RecordSqlParameters parameters) {
    final String targetType;
    final String floor;
    final boolean aligned;
    if (type == LogicalFieldType.TIME) {
      kind(value, LogicalValueKind.TIME);
      LocalTime time = LocalTime.parse(value.canonicalValue());
      aligned = time.getNano() % 1000 == 0;
      floor = time.truncatedTo(ChronoUnit.MICROS).format(DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSS"));
      targetType = "TIME(6)";
    } else {
      kind(value, LogicalValueKind.DATETIME);
      Instant instant = Instant.parse(value.canonicalValue());
      aligned = instant.getNano() % 1000 == 0;
      floor = LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC)
          .format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS"));
      targetType = "TIMESTAMP(6)";
    }
    // Runtime physical temporal columns have microsecond precision. Compare an off-grid
    // threshold mathematically instead of rounding it, which would reverse boundary results.
    if (!aligned && "=".equals(symbol)) {
      return "1 = 0";
    }
    String comparison = !aligned && "<".equals(symbol) ? "<=" : !aligned && ">=".equals(symbol) ? ">" : symbol;
    return column + " " + comparison + " CAST(" + textParameter(floor, parameters) + " AS " + targetType + ")";
  }

  private static String textEquality(final String column, final String value, final RecordSqlParameters parameters) {
    // REPLACE matches exact text in the supported PostgreSQL/MySQL dialects. Comparing the
    // lengths as well prevents a repetition of the expected text from looking equal. No
    // collation-sensitive text equality, trimming, hash collision or service-side scan is involved.
    return "(CHAR_LENGTH(" + column + ") = CHAR_LENGTH(" + textParameter(value, parameters) + ")"
        + " AND CHAR_LENGTH(REPLACE(" + column + ", " + textParameter(value, parameters) + ", '')) = 0)";
  }

  private static String textParameter(final String value, final RecordSqlParameters parameters) {
    return "CAST(" + parameters.add(value) + " AS VARCHAR(" + Math.max(1, value.length()) + "))";
  }

  private static void equalityOnly(final String symbol) {
    if (!"=".equals(symbol)) {
      throw invalid();
    }
  }

  private static void kind(final LogicalValue value, final LogicalValueKind expected) {
    if (value.kind() != expected) {
      throw invalid();
    }
  }

  private static DnaRuntimeException invalid() {
    return new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_QUERY_INVALID,
        "Inspection condition requires known readable fields, supported comparisons and matching typed literals");
  }
}
