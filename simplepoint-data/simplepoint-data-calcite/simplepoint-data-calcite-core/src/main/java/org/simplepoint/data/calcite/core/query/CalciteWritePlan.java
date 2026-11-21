package org.simplepoint.data.calcite.core.query;

import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Bound JDBC tables from the actual prepared DML plan, before optimization can remove inputs. Local
 * execution evidence only: not a transport DTO or proof of native trigger/function safety.
 */
public record CalciteWritePlan(Operation operation, List<TableUse> tables) {
  /** Makes the plan immutable and requires exactly one write target. */
  public CalciteWritePlan {
    Objects.requireNonNull(operation, "operation");
    tables = List.copyOf(tables);
    if (tables.isEmpty()
        || tables.size() > 256
        || tables.stream().filter(table -> table.role() == Role.TARGET).count() != 1) {
      throw new CalciteWritePlanException(CalciteWritePlanException.Code.UNSUPPORTED_PLAN);
    }
  }

  /** Standard DML operations supported by the engine. */
  public enum Operation {
    INSERT,
    UPDATE,
    DELETE
  }

  /** A target may also occur as a read input; those uses remain distinct. */
  public enum Role {
    TARGET,
    READ
  }

  /**
   * Physical names are supplied by the JDBC adapter. Match the datasource by object identity, never
   * by URL, equals(), or the logical directory path; retain execution-owner connections.
   */
  public record TableUse(
      Role role,
      DataSource dataSource,
      String catalog,
      String schema,
      String table,
      List<String> logicalPath) {
    /**
     * Retains the actual adapter source and an immutable logical path for correspondence checks.
     */
    public TableUse {
      Objects.requireNonNull(role, "role");
      Objects.requireNonNull(dataSource, "dataSource");
      Objects.requireNonNull(table, "table");
      logicalPath = List.copyOf(logicalPath);
      if (table.isBlank() || logicalPath.isEmpty()) {
        throw new CalciteWritePlanException(CalciteWritePlanException.Code.UNSUPPORTED_PLAN);
      }
    }
  }
}
