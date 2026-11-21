package org.simplepoint.data.calcite.core.query;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.function.Supplier;
import org.apache.calcite.adapter.jdbc.JdbcTable;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableFunctionScan;
import org.apache.calcite.rel.core.TableModify;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexFieldAccess;
import org.apache.calcite.rex.RexLambda;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexOver;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexSubQuery;

/** Bounded traversal of the prepared relational plan, including expression subqueries. */
final class CalciteWritePlanCollector {
  private CalciteWritePlanCollector() {}

  static CalciteWritePlan collect(RelNode root) {
    if (!(root instanceof TableModify modification)) {
      throw unsupported();
    }
    var operation =
        switch (modification.getOperation()) {
          case INSERT -> CalciteWritePlan.Operation.INSERT;
          case UPDATE -> CalciteWritePlan.Operation.UPDATE;
          case DELETE -> CalciteWritePlan.Operation.DELETE;
          default -> throw unsupported();
        };
    var tables = new ArrayList<CalciteWritePlan.TableUse>();
    var pending = new ArrayDeque<Visit>();
    var expressions = new ExpressionDependencies(pending);
    var visited = Collections.newSetFromMap(new IdentityHashMap<RelNode, Boolean>());
    pending.add(new Visit(root, 0));
    while (!pending.isEmpty()) {
      var visit = pending.removeFirst();
      if (visit.depth() > 64 || visited.size() >= 4096 || pending.size() > 4096) {
        throw new CalciteWritePlanException(CalciteWritePlanException.Code.PLAN_LIMIT_EXCEEDED);
      }
      var node = visit.node();
      if (!visited.add(node)) {
        continue;
      }
      if (node instanceof TableFunctionScan || node instanceof TableModify && node != root) {
        throw unsupported();
      }
      if (node.getTable() != null) {
        if (node != root && !(node instanceof TableScan)) {
          throw unsupported();
        }
        var table = node.getTable().unwrap(JdbcTable.class);
        if (table == null || tables.size() >= 256) {
          throw unsupported();
        }
        tables.add(
            new CalciteWritePlan.TableUse(
                node == root ? CalciteWritePlan.Role.TARGET : CalciteWritePlan.Role.READ,
                table.jdbcSchema.getDataSource(),
                table.jdbcCatalogName,
                table.jdbcSchemaName,
                table.jdbcTableName,
                node.getTable().getQualifiedName()));
      } else if (node == root) {
        throw unsupported();
      }
      for (var input : node.getInputs()) {
        pending.addLast(new Visit(input, visit.depth() + 1));
      }
      expressions.relationalDepth = visit.depth();
      node.accept(expressions);
    }
    return new CalciteWritePlan(operation, tables);
  }

  private static CalciteWritePlanException unsupported() {
    return new CalciteWritePlanException(CalciteWritePlanException.Code.UNSUPPORTED_PLAN);
  }

  private record Visit(RelNode node, int depth) {}

  /** Bounds recursive Rex traversal as well as the outer iterative relational traversal. */
  private static final class ExpressionDependencies extends RexShuttle {
    private final ArrayDeque<Visit> pending;
    private int relationalDepth;
    private int depth;
    private int operators;

    private ExpressionDependencies(ArrayDeque<Visit> pending) {
      this.pending = pending;
    }

    private RexNode within(Supplier<RexNode> visitor) {
      if (++operators > 4096 || relationalDepth + ++depth > 64) {
        throw new CalciteWritePlanException(CalciteWritePlanException.Code.PLAN_LIMIT_EXCEEDED);
      }
      try {
        return visitor.get();
      } finally {
        depth--;
      }
    }

    @Override
    public RexNode visitSubQuery(RexSubQuery query) {
      return within(
          () -> {
            pending.addLast(new Visit(query.rel, relationalDepth + depth));
            return super.visitSubQuery(query);
          });
    }

    @Override
    public RexNode visitCall(RexCall call) {
      return within(() -> super.visitCall(call));
    }

    @Override
    public RexNode visitOver(RexOver over) {
      return within(() -> super.visitOver(over));
    }

    @Override
    public RexNode visitFieldAccess(RexFieldAccess access) {
      return within(() -> super.visitFieldAccess(access));
    }

    @Override
    public RexNode visitLambda(RexLambda lambda) {
      return within(() -> super.visitLambda(lambda));
    }
  }
}
