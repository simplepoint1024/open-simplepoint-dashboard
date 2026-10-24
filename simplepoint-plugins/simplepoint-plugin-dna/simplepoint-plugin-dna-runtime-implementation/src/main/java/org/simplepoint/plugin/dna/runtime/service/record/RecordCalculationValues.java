package org.simplepoint.plugin.dna.runtime.service.record;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import org.simplepoint.calculation.FormulaException;
import org.simplepoint.calculation.FormulaValues;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;

/** Adapts calculations through the same exact physical type contract as ordinary Record writes. */
final class RecordCalculationValues {
  private static final StandardLogicalValueConverter VALUES = new StandardLogicalValueConverter();

  private RecordCalculationValues() {
  }

  static Object typed(final LogicalFieldDefinition field, final Object value) {
    Object candidate = value;
    if (value != null && (field.type() == LogicalFieldType.DECIMAL || field.type() == LogicalFieldType.INT32
        || field.type() == LogicalFieldType.INT64)) {
      if (value instanceof Double || value instanceof Float || !(value instanceof Number || value instanceof String)) {
        throw new FormulaException("RESULT_TYPE");
      }
      var number = FormulaValues.number(value);
      try {
        candidate = field.type() == LogicalFieldType.DECIMAL ? number.setScale(field.scale(), RoundingMode.UNNECESSARY) : number;
      } catch (ArithmeticException failure) {
        throw new FormulaException("RESULT_RANGE");
      }
    }
    if (field.type() == LogicalFieldType.DATETIME && value instanceof LocalDateTime local) {
      candidate = local.toInstant(ZoneOffset.UTC);
    }
    var normalized = VALUES.normalize(field, candidate);
    String text = normalized.canonicalValue();
    return switch (normalized.kind()) {
      case NULL -> null;
      case INTEGER, DECIMAL -> new BigDecimal(text);
      case BOOLEAN -> Boolean.valueOf(text);
      case DATE -> LocalDate.parse(text);
      case TIME -> LocalTime.parse(text);
      case DATETIME -> LocalDateTime.ofInstant(Instant.parse(text), ZoneOffset.UTC);
      case STRING, UUID, JSON, BINARY -> text;
    };
  }

  /** Uses the precision-safe Record wire shape accepted by the existing SQL compiler. */
  static Object wire(final LogicalFieldDefinition field, final Object typed) {
    if (typed == null) {
      return null;
    }
    return switch (field.type()) {
      case INT32 -> ((BigDecimal) typed).intValueExact();
      case INT64, DECIMAL -> ((BigDecimal) typed).toPlainString();
      case DATE, TIME -> typed.toString();
      case DATETIME -> ((LocalDateTime) typed).toInstant(ZoneOffset.UTC).toString();
      default -> typed;
    };
  }
}
