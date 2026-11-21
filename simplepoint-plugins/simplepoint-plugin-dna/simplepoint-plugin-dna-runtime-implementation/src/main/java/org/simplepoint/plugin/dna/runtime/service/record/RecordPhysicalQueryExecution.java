package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.function.Supplier;
import org.apache.calcite.adapter.enumerable.EnumerableRules;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.rel.rules.CoreRules;
import org.apache.calcite.runtime.Hook;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;

/**
 * Bounds relational work at the database. Unsupported collection plans fail before data execution;
 * a small result limit must not silently transfer an unbounded relation to an in-process join.
 */
final class RecordPhysicalQueryExecution {
  private RecordPhysicalQueryExecution() {}

  static CalciteQueryResult execute(Supplier<CalciteQueryResult> query) {
    try (Hook.Closeable ignored =
        Hook.PLANNER.addThread(
            (RelOptPlanner planner) -> {
              EnumerableRules.ENUMERABLE_RULES.stream()
                  .filter(rule -> rule != EnumerableRules.ENUMERABLE_VALUES_RULE)
                  .forEach(planner::removeRule);
              planner.removeRule(EnumerableRules.ENUMERABLE_SORTED_AGGREGATE_RULE);
              planner.removeRule(EnumerableRules.ENUMERABLE_BATCH_NESTED_LOOP_JOIN_RULE);
              // Constant-false authorization may fold to an empty value relation without reading a
              // table.
              planner.addRule(CoreRules.AGGREGATE_VALUES);
              planner.addRule(CoreRules.PROJECT_VALUES_MERGE);
            })) {
      return query.get();
    }
  }
}
