package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.List;
import java.util.Objects;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;

/**
 * Immutable typed condition for owner inspections. Comparisons with null are false before
 * negation; text equality is exact regardless of database collation. Empty uses completeness
 * semantics (null or Unicode whitespace for text; JSON follows the query's explicit semantics version).
 */
public record RecordValueCondition(
    RecordValueConditionOperator operator,
    String field,
    List<LogicalValue> values,
    List<RecordValueCondition> children
) {
  /** Rejects executable fragments, malformed nodes and oversized literal lists. */
  public RecordValueCondition {
    Objects.requireNonNull(operator, "operator");
    values = values == null ? List.of() : List.copyOf(values);
    children = children == null ? List.of() : List.copyOf(children);
    if (operator.constant() || operator.group()) {
      if (field != null || !values.isEmpty() || operator.constant() && !children.isEmpty()
          || operator.group() && children.isEmpty() || operator == RecordValueConditionOperator.NOT && children.size() != 1) {
        throw new IllegalArgumentException("Invalid inspection condition group");
      }
    } else {
      if (field == null || !field.matches("[a-z][a-z0-9_]{0,62}") || !children.isEmpty()) {
        throw new IllegalArgumentException("Invalid inspection condition field");
      }
      boolean empty = operator == RecordValueConditionOperator.IS_EMPTY || operator == RecordValueConditionOperator.IS_NOT_EMPTY;
      boolean multiple = operator == RecordValueConditionOperator.IN || operator == RecordValueConditionOperator.NOT_IN;
      if (empty && !values.isEmpty() || !empty && (values.isEmpty() || values.size() > (multiple ? 100 : 1))) {
        throw new IllegalArgumentException("Invalid inspection condition value count");
      }
    }
    if (values.stream().anyMatch(value -> value.kind() == LogicalValueKind.NULL || value.canonicalValue().length() > 2000)) {
      throw new IllegalArgumentException("Inspection conditions require bounded non-null literals");
    }
  }

  /** Checks expanded traversal cost before compilation, including repeated subtrees. */
  public static RecordValueCondition bounded(final RecordValueCondition condition) {
    if (condition != null) {
      condition.complexity();
    }
    return condition;
  }

  /** Bounds both node traversal and typed literal compilation work. */
  public int complexity() {
    int[] cost = {0};
    visit(this, 1, cost);
    return cost[0];
  }

  private static void visit(final RecordValueCondition condition, final int depth, final int[] nodes) {
    nodes[0] += 1 + condition.values().size();
    if (depth > 32 || nodes[0] > 4096) {
      throw new IllegalArgumentException("Inspection condition exceeds the complexity budget");
    }
    condition.children().forEach(child -> visit(child, depth + 1, nodes));
  }
}
