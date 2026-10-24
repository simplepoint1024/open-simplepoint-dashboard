package org.simplepoint.plugin.dna.runtime.api.record;

import java.math.BigDecimal;
import java.util.Objects;

/** Immutable aggregate invariant, without UI messages, executable code or request authorization. */
public record RecordValidationRule(String ruleId, Kind kind, RecordValidationField field,
    RecordValidationField other, String detailId, String minimum, String maximum, RecordValidationCondition when,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    RecordFieldConstraints constraints) {
  /** Supported invariants; RANGE on a detail field applies independently to every owned row. */
  public enum Kind { REQUIRED, RANGE, AFTER, DETAIL_COUNT, DETAIL_SUM, FIELD }

  /** Preserves the shape of existing immutable aggregate rules. */
  public RecordValidationRule(final String ruleId, final Kind kind, final RecordValidationField field,
      final RecordValidationField other, final String detailId, final String minimum, final String maximum,
      final RecordValidationCondition when) {
    this(ruleId, kind, field, other, detailId, minimum, maximum, when, null);
  }

  /** Rejects unused attributes and invalid bounds so immutable definitions have one meaning. */
  public RecordValidationRule {
    RecordCalculationDefinition.identity(ruleId);
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(when, "when").complexity();
    boolean range = kind == Kind.RANGE || kind == Kind.DETAIL_COUNT;
    boolean pair = kind == Kind.AFTER || kind == Kind.DETAIL_SUM;
    if ((kind == Kind.FIELD) != (constraints != null)
        || (kind == Kind.DETAIL_COUNT) != (detailId != null)
        || (kind != Kind.DETAIL_COUNT) != (field != null) || pair != (other != null)
        || !range && (minimum != null || maximum != null)
        || range && minimum == null && maximum == null) {
      throw new IllegalArgumentException("Invalid aggregate validation rule shape");
    }
    if (detailId != null) {
      RecordCalculationDefinition.identity(detailId);
      if ("parent".equals(detailId)) {
        throw new IllegalArgumentException("A detail count requires an owned scope");
      }
    }
    if (kind == Kind.AFTER && (!field.scope().equals(other.scope()) || field.equals(other))
        || kind == Kind.DETAIL_SUM && (!"parent".equals(field.scope()) || "parent".equals(other.scope()))) {
      throw new IllegalArgumentException("Invalid aggregate validation relation");
    }
    BigDecimal min = bound(minimum);
    BigDecimal max = bound(maximum);
    if (min != null && max != null && min.compareTo(max) > 0) {
      throw new IllegalArgumentException("Invalid aggregate validation range");
    }
    if (kind == Kind.DETAIL_COUNT) {
      count(min);
      count(max);
    }
  }

  /** The row context in which this rule and its condition are evaluated. */
  public String evaluationScope() {
    return kind == Kind.DETAIL_COUNT || kind == Kind.DETAIL_SUM ? "parent" : field.scope();
  }

  private static BigDecimal bound(final String value) {
    if (value == null) {
      return null;
    }
    if (value.length() > 80 || !value.matches("-?[0-9]+(\\.[0-9]+)?")) {
      throw new IllegalArgumentException("Invalid aggregate validation bound");
    }
    return new BigDecimal(value);
  }

  private static void count(final BigDecimal value) {
    try {
      if (value != null && (value.intValueExact() < 0 || value.intValueExact() > 40)) {
        throw new IllegalArgumentException("Invalid detail count bound");
      }
    } catch (ArithmeticException failure) {
      throw new IllegalArgumentException("Detail count bound must be an integer", failure);
    }
  }
}
