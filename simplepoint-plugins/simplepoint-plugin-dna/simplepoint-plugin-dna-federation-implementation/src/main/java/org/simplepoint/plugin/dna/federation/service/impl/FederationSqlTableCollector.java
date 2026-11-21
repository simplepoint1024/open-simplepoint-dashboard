package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.impl.FederationSqlStatementBoundary.unsupported;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDelete;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlMerge;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlUpdate;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.SqlWithItem;

/** Bounded, scope-aware table references shared by query and write admission. */
final class FederationSqlTableCollector {
  private final List<TableUse> tables = new ArrayList<>();
  private final boolean queryOnly;
  private final FederationSqlIdentifierSource source;
  private int visited;

  FederationSqlTableCollector(boolean queryOnly, FederationSqlIdentifierSource source) {
    this.queryOnly = queryOnly;
    this.source = source;
  }

  List<SqlIdentifier> tables() {
    return tables.stream().map(TableUse::identifier).toList();
  }

  List<TableUse> uses() {
    return List.copyOf(tables);
  }

  private void budget(int depth) {
    if (++visited > 4096 || depth > 64 || tables.size() > 256) {
      throw unsupported();
    }
  }

  private void target(SqlNode node, boolean write) {
    if (!(node instanceof SqlIdentifier id)
        || id.isStar()
        || id.names.isEmpty()
        || id.names.size() > 4) {
      throw unsupported();
    }
    if (tables.size() >= 256) {
      throw unsupported();
    }
    tables.add(new TableUse(id, write));
  }

  void walk(SqlNode node, Set<CteBinding> ctes, int depth) {
    if (node == null) {
      return;
    }
    budget(depth);
    if (queryOnly && (node.isA(SqlKind.DML) || node.isA(SqlKind.DDL))) {
      throw unsupported();
    }
    if (node instanceof SqlInsert insert) {
      target(insert.getTargetTable(), true);
      walk(insert.getSource(), ctes, depth + 1);
    } else if (node instanceof SqlUpdate update) {
      target(update.getTargetTable(), true);
      walk(update.getSourceExpressionList(), ctes, depth + 1);
      walk(update.getCondition(), ctes, depth + 1);
    } else if (node instanceof SqlDelete delete) {
      target(delete.getTargetTable(), true);
      walk(delete.getCondition(), ctes, depth + 1);
    } else if (node instanceof SqlMerge merge) {
      target(merge.getTargetTable(), true);
      from(merge.getSourceTableRef(), ctes, depth + 1);
      walk(merge.getCondition(), ctes, depth + 1);
      walk(merge.getUpdateCall(), ctes, depth + 1);
      walk(merge.getInsertCall(), ctes, depth + 1);
    } else if (node instanceof SqlWith with) {
      var local = new HashSet<>(ctes);
      for (var item : with.withList) {
        var binding = (SqlWithItem) item;
        if (binding.recursive.booleanValue()) {
          local.add(new CteBinding(binding.name.getSimple(), source.bindingKey(binding.name)));
        }
        walk(binding.query, Set.copyOf(local), depth + 1);
        local.add(new CteBinding(binding.name.getSimple(), source.bindingKey(binding.name)));
      }
      walk(with.body, Set.copyOf(local), depth + 1);
    } else if (node instanceof SqlSelect select) {
      from(select.getFrom(), ctes, depth + 1);
      // FROM needs scope-aware handling; every other operand can still contain a subquery.
      for (var operand : select.getOperandList()) {
        if (operand != select.getFrom()) {
          walk(operand, ctes, depth + 1);
        }
      }
    } else if (node instanceof SqlOrderBy order) {
      walk(order.query, ctes, depth + 1);
      walk(order.orderList, ctes, depth + 1);
      walk(order.offset, ctes, depth + 1);
      walk(order.fetch, ctes, depth + 1);
    } else if (node instanceof SqlCall call && call.getKind() == SqlKind.EXPLICIT_TABLE) {
      if (call.operandCount() != 1) {
        throw unsupported();
      }
      from(call.operand(0), ctes, depth + 1);
    } else if (node instanceof SqlNodeList list) {
      for (var item : list) {
        walk(item, ctes, depth + 1);
      }
    } else if (node instanceof SqlCall call) {
      for (var operand : call.getOperandList()) {
        walk(operand, ctes, depth + 1);
      }
    }
  }

  private void from(SqlNode node, Set<CteBinding> ctes, int depth) {
    if (node == null) {
      return;
    }
    budget(depth);
    if (node instanceof SqlIdentifier id) {
      if (id.names.size() != 1 || !bound(id, ctes)) {
        target(id, false);
      }
    } else if (node instanceof SqlJoin join) {
      from(join.getLeft(), ctes, depth + 1);
      from(join.getRight(), ctes, depth + 1);
      walk(join.getCondition(), ctes, depth + 1);
    } else if (node instanceof SqlCall call && call.getKind() == SqlKind.AS) {
      from(call.getOperandList().getFirst(), ctes, depth + 1);
    } else if (node instanceof SqlSelect
        || node instanceof SqlWith
        || node instanceof SqlOrderBy
        || node.getKind() == SqlKind.EXPLICIT_TABLE
        || node.getKind() == SqlKind.UNION
        || node.getKind() == SqlKind.INTERSECT
        || node.getKind() == SqlKind.EXCEPT
        || node.getKind() == SqlKind.VALUES) {
      walk(node, ctes, depth + 1);
    } else {
      // Dynamic table functions and unrecognized FROM operators cannot prove a bounded footprint.
      throw unsupported();
    }
  }

  private boolean bound(SqlIdentifier identifier, Set<CteBinding> ctes) {
    var matches =
        ctes.stream()
            .filter(binding -> binding.parsedName().equals(identifier.getSimple()))
            .toList();
    if (matches.isEmpty()) {
      return false;
    }
    String key = source.bindingKey(identifier);
    if (matches.size() != 1 || !matches.getFirst().sourceKey().equals(key)) {
      throw unsupported();
    }
    return true;
  }

  record TableUse(SqlIdentifier identifier, boolean write) {}

  record CteBinding(String parsedName, String sourceKey) {}
}
