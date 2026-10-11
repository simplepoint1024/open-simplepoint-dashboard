package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** One validated node in a structured record filter tree. */
public record RecordFilter(
    RecordFilterOperator operator,
    String field,
    List<Object> values,
    List<RecordFilter> children
) {

  private static final Pattern FIELD = Pattern.compile("[a-z][a-z0-9_]{0,62}");

  /** Validates local operator shape; request objects enforce whole-tree budgets. */
  public RecordFilter {
    Objects.requireNonNull(operator, "operator");
    values = values == null ? List.of() : List.copyOf(values);
    children = children == null ? List.of() : List.copyOf(children);
    if (operator.group()) {
      if (field != null || !values.isEmpty() || children.isEmpty()
          || operator == RecordFilterOperator.NOT && children.size() != 1) {
        throw new IllegalArgumentException("Record filter group has an invalid shape");
      }
    } else {
      if (field == null || !FIELD.matcher(field).matches() || !children.isEmpty()) {
        throw new IllegalArgumentException("Record filter predicate has an invalid shape");
      }
      int expected = switch (operator) {
        case IS_NULL, IS_NOT_NULL -> 0;
        case BETWEEN, NOT_BETWEEN -> 2;
        default -> -1;
      };
      if (expected >= 0 && values.size() != expected
          || expected < 0 && values.isEmpty()) {
        throw new IllegalArgumentException("Record filter value count is invalid");
      }
      if ((operator == RecordFilterOperator.IN || operator == RecordFilterOperator.NOT_IN)
          && values.size() > 100) {
        throw new IllegalArgumentException("Record filter list exceeds 100 values");
      }
      if (operator != RecordFilterOperator.IN
          && operator != RecordFilterOperator.NOT_IN
          && expected < 0
          && values.size() != 1) {
        throw new IllegalArgumentException("Record filter requires exactly one value");
      }
    }
  }
}
