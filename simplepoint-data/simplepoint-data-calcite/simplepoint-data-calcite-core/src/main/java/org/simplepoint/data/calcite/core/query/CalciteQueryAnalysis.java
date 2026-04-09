package org.simplepoint.data.calcite.core.query;

import java.util.List;

/**
 * Explain-plan analysis summary for a Calcite query.
 *
 * @param planText            explain-plan text
 * @param pushedDownOperators recognized JDBC pushdown operators
 * @param platformJoin        whether the plan still contains a platform-side join operator
 * @param pushedSqls          JDBC SQL statements captured during backend implementation/execution
 */
public record CalciteQueryAnalysis(
    String planText,
    List<String> pushedDownOperators,
    boolean platformJoin,
    List<String> pushedSqls
) {

  /**
   * Creates an immutable analysis payload without captured pushed SQL.
   *
   * @param planText            explain-plan text
   * @param pushedDownOperators recognized JDBC pushdown operators
   * @param platformJoin        whether the plan still contains a platform-side join operator
   */
  public CalciteQueryAnalysis(
      final String planText,
      final List<String> pushedDownOperators,
      final boolean platformJoin
  ) {
    this(planText, pushedDownOperators, platformJoin, List.of());
  }

  /**
   * Creates an immutable analysis payload.
   *
   * @param planText            explain-plan text
   * @param pushedDownOperators recognized JDBC pushdown operators
   * @param platformJoin        whether the plan still contains a platform-side join operator
   * @param pushedSqls          JDBC SQL statements captured during backend implementation/execution
   */
  public CalciteQueryAnalysis {
    planText = planText == null ? "" : planText;
    pushedDownOperators = pushedDownOperators == null ? List.of() : List.copyOf(pushedDownOperators);
    pushedSqls = pushedSqls == null ? List.of() : List.copyOf(pushedSqls);
  }
}
