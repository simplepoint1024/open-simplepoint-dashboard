package org.simplepoint.plugin.dna.federation.api.vo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Request and response models used by the federation SQL console.
 */
public final class FederationQueryModels {

  private FederationQueryModels() {
  }

  /**
   * SQL console request payload.
   *
   * @param catalogCode target federation catalog code
   * @param sql         SQL text
   * @param defaultSchema optional default schema for unqualified identifiers
   */
  public record SqlConsoleRequest(
      String catalogCode,
      String sql,
      String defaultSchema
  ) {

    public SqlConsoleRequest(final String catalogCode, final String sql) {
      this(catalogCode, sql, null);
    }
  }

  /**
   * Result-set column metadata.
   *
   * @param name     column label
   * @param typeName JDBC type name
   * @param jdbcType JDBC type code
   */
  public record SqlColumn(
      String name,
      String typeName,
      Integer jdbcType
  ) {

    public SqlColumn(final String name, final String typeName) {
      this(name, typeName, null);
    }
  }

  /**
   * Explain-plan response payload.
   *
   * @param catalogCode           target federation catalog code
   * @param policyCode            applied query policy code
   * @param maxRows               effective max rows
   * @param timeoutMs             effective timeout in milliseconds
   * @param allowCrossSourceJoin  whether the effective policy allows cross-source joins
   * @param crossSourceJoin       whether the analyzed query spans multiple physical sources
   * @param dataSources           physical datasource codes used by the analyzed plan
   * @param planText              explain-plan text
   * @param pushedSqls            JDBC SQL statements captured during execution planning/implementation
   * @param pushdownSummary       pushdown summary text
   */
  public record SqlExplainResult(
      String catalogCode,
      String policyCode,
      int maxRows,
      int timeoutMs,
      boolean allowCrossSourceJoin,
      boolean crossSourceJoin,
      List<String> dataSources,
      String planText,
      List<String> pushedSqls,
      String pushdownSummary
  ) {

    /**
     * Creates an immutable explain response payload.
     *
     * @param catalogCode          target federation catalog code
     * @param policyCode           applied query policy code
     * @param maxRows              effective max rows
     * @param timeoutMs            effective timeout in milliseconds
     * @param allowCrossSourceJoin whether the effective policy allows cross-source joins
     * @param crossSourceJoin      whether the analyzed query spans multiple physical sources
     * @param dataSources          physical datasource codes used by the analyzed plan
     * @param planText             explain-plan text
     * @param pushedSqls           JDBC SQL statements captured during execution planning/implementation
     * @param pushdownSummary      pushdown summary text
     */
    public SqlExplainResult {
      dataSources = dataSources == null ? List.of() : List.copyOf(dataSources);
      planText = planText == null ? "" : planText;
      pushedSqls = pushedSqls == null ? List.of() : List.copyOf(pushedSqls);
    }
  }

  /**
   * DML (INSERT / UPDATE / DELETE / UPSERT) execution response payload.
   *
   * @param catalogCode     target federation catalog code
   * @param dataSourceCode  physical datasource code that executed the statement
   * @param affectedRows    number of rows affected by the statement
   * @param executionTimeMs execution time in milliseconds
   * @param pushedSql       actual SQL pushed to the physical database
   */
  public record SqlUpdateResult(
      String catalogCode,
      String dataSourceCode,
      long affectedRows,
      long executionTimeMs,
      String pushedSql
  ) {
  }

  /**
   * Unified execution response returned by the smart {@code /execute} endpoint.
   * Wraps one of the concrete result types and exposes a {@code type} discriminator
   * so the frontend can render the appropriate view.
   *
   * @param type         one of {@code QUERY}, {@code DML}, {@code DDL}, {@code FLUSH_CACHE}, {@code EXPLAIN}
   * @param queryResult  present when type is QUERY
   * @param updateResult present when type is DML or DDL
   * @param message      present when type is FLUSH_CACHE (success message)
   */
  public record SqlExecuteResult(
      String type,
      SqlQueryResult queryResult,
      SqlUpdateResult updateResult,
      String message
  ) {

    /** Creates a QUERY result. */
    public static SqlExecuteResult query(final SqlQueryResult result) {
      return new SqlExecuteResult("QUERY", result, null, null);
    }

    /** Creates a DML result. */
    public static SqlExecuteResult dml(final SqlUpdateResult result) {
      return new SqlExecuteResult("DML", null, result, null);
    }

    /** Creates a DDL result. */
    public static SqlExecuteResult ddl(final SqlUpdateResult result) {
      return new SqlExecuteResult("DDL", null, result, null);
    }

    /** Creates a FLUSH_CACHE result. */
    public static SqlExecuteResult flushCache(final String successMessage) {
      return new SqlExecuteResult("FLUSH_CACHE", null, null, successMessage);
    }
  }

  /**
   * Query execution response payload.
   *
   * @param catalogCode           target federation catalog code
   * @param policyCode            applied query policy code
   * @param maxRows               effective max rows
   * @param timeoutMs             effective timeout in milliseconds
   * @param allowCrossSourceJoin  whether the effective policy allows cross-source joins
   * @param crossSourceJoin       whether the executed query spans multiple physical sources
   * @param dataSources           physical datasource codes used by the execution plan
   * @param columns               result-set columns
   * @param rows                  result rows
   * @param truncated             whether the result was truncated by the effective max-rows policy
   * @param returnedRows          returned row count
   * @param executionTimeMs       execution time in milliseconds
   * @param planText              explain-plan text
   * @param pushedSqls            JDBC SQL statements captured during execution planning/implementation
   * @param pushdownSummary       pushdown summary text
   */
  public record SqlQueryResult(
      String catalogCode,
      String policyCode,
      int maxRows,
      int timeoutMs,
      boolean allowCrossSourceJoin,
      boolean crossSourceJoin,
      List<String> dataSources,
      List<SqlColumn> columns,
      List<List<Object>> rows,
      boolean truncated,
      long returnedRows,
      long executionTimeMs,
      String planText,
      List<String> pushedSqls,
      String pushdownSummary
  ) {

    /**
     * Creates an immutable query response payload while preserving nullable result cells.
     *
     * @param catalogCode          target federation catalog code
     * @param policyCode           applied query policy code
     * @param maxRows              effective max rows
     * @param timeoutMs            effective timeout in milliseconds
     * @param allowCrossSourceJoin whether the effective policy allows cross-source joins
     * @param crossSourceJoin      whether the executed query spans multiple physical sources
     * @param dataSources          physical datasource codes used by the execution plan
     * @param columns              result-set columns
     * @param rows                 result rows
     * @param truncated            whether the result was truncated by the effective max-rows policy
     * @param returnedRows         returned row count
     * @param executionTimeMs      execution time in milliseconds
     * @param planText             explain-plan text
     * @param pushedSqls           JDBC SQL statements captured during execution planning/implementation
     * @param pushdownSummary      pushdown summary text
     */
    public SqlQueryResult {
      dataSources = dataSources == null ? List.of() : List.copyOf(dataSources);
      columns = columns == null ? List.of() : List.copyOf(columns);
      rows = rows == null ? List.of() : rows.stream()
          .map(row -> row == null ? List.<Object>of() : Collections.unmodifiableList(new ArrayList<>(row)))
          .toList();
      planText = planText == null ? "" : planText;
      pushedSqls = pushedSqls == null ? List.of() : List.copyOf(pushedSqls);
    }
  }
}
