package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.List;
import java.util.Objects;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;

/** Bounded two-valued condition over stable fields, evaluated after aggregate calculation. */
public record RecordValidationCondition(RecordValueConditionOperator operator, RecordValidationField field,
    List<LogicalValue> values, List<RecordValidationCondition> children) {
  /** Rejects ambiguous shapes; publication compilation additionally resolves field types and scopes. */
  public RecordValidationCondition {
    Objects.requireNonNull(operator, "operator");
    values = values == null ? List.of() : List.copyOf(values);
    children = children == null ? List.of() : List.copyOf(children);
    if (operator.constant() || operator.group()) {
      if (field != null || !values.isEmpty() || operator.constant() && !children.isEmpty()
          || operator.group() && children.isEmpty() || operator == RecordValueConditionOperator.NOT && children.size() != 1) {
        throw new IllegalArgumentException("Invalid aggregate condition group");
      }
    } else {
      boolean empty = operator == RecordValueConditionOperator.IS_EMPTY || operator == RecordValueConditionOperator.IS_NOT_EMPTY;
      boolean multiple = operator == RecordValueConditionOperator.IN || operator == RecordValueConditionOperator.NOT_IN;
      if (field == null || !children.isEmpty() || empty && !values.isEmpty()
          || !empty && (values.isEmpty() || values.size() > (multiple ? 100 : 1))) {
        throw new IllegalArgumentException("Invalid aggregate condition field or literals");
      }
    }
    if (values.stream().anyMatch(value -> value.kind() == LogicalValueKind.NULL || value.canonicalValue().length() > 2000)) {
      throw new IllegalArgumentException("Aggregate conditions require bounded non-null literals");
    }
  }

  /** Counts expanded traversal, including reused subtrees, before any recursive compilation. */
  public int complexity() {
    int[] count = {0};
    visit(this, 1, count);
    return count[0];
  }

  /** Counts literal storage only after the traversal budget has been checked. */
  public long textBudget() {
    complexity();
    return text(this);
  }

  private static long text(final RecordValidationCondition condition) {
    return condition.values().stream().mapToLong(value -> value.canonicalValue().length()).sum()
        + condition.children().stream().mapToLong(RecordValidationCondition::text).sum();
  }

  private static void visit(final RecordValidationCondition condition, final int depth, final int[] count) {
    count[0] += 1 + condition.values().size();
    if (depth > 32 || count[0] > 4096) {
      throw new IllegalArgumentException("Aggregate condition exceeds the complexity budget");
    }
    condition.children().forEach(child -> visit(child, depth + 1, count));
  }
}
