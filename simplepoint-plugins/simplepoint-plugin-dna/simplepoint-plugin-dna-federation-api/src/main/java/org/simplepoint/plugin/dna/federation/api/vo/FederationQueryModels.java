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
   */
  public record SqlConsoleRequest(
      String catalogCode,
      String sql
  ) {
  }

  /**
   * Result-set column metadata.
   *
   * @param name     column label
   * @param typeName JDBC type name
   */
  public record SqlColumn(
      String name,
      String typeName
  ) {
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
