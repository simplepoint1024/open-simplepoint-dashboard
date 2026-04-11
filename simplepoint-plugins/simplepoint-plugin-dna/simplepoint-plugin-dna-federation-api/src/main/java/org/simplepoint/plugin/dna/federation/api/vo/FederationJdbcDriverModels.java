package org.simplepoint.plugin.dna.federation.api.vo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Request and response models used by the external DNA JDBC driver gateway.
 */
public final class FederationJdbcDriverModels {

  private FederationJdbcDriverModels() {
  }

  /**
   * Driver authentication and routing request.
   *
   * @param loginSubject login subject, usually email or phone number
   * @param password user password
   * @param catalogCode optional default datasource catalog code
   * @param tenantId optional tenant id
   * @param contextId optional permission context id
   */
  public record DriverRequest(
      String loginSubject,
      String password,
      String catalogCode,
      String tenantId,
      String contextId
  ) {
  }

  /**
   * Driver query request payload.
   *
   * @param sql SQL text
   * @param defaultSchema optional default schema
   * @param catalogCode optional datasource catalog code used for this query
   */
  public record QueryRequest(
      String sql,
      String defaultSchema,
      String catalogCode
  ) {
    public QueryRequest(final String sql, final String defaultSchema) {
      this(sql, defaultSchema, null);
    }
  }

  /**
   * Result-set column metadata.
   *
   * @param name column label
   * @param typeName JDBC type name
   * @param jdbcType JDBC type code
   */
  public record JdbcColumn(
      String name,
      String typeName,
      int jdbcType
  ) {
  }

  /**
   * Generic tabular result for JDBC metadata queries.
   *
   * @param columns columns
   * @param rows rows
   */
  public record TabularResult(
      List<JdbcColumn> columns,
      List<List<Object>> rows
  ) {

    /**
     * Creates an immutable tabular result.
     *
     * @param columns columns
     * @param rows rows
     */
    public TabularResult {
      columns = columns == null ? List.of() : List.copyOf(columns);
      rows = rows == null ? List.of() : rows.stream()
          .map(row -> row == null ? List.<Object>of() : Collections.unmodifiableList(new ArrayList<>(row)))
          .toList();
    }
  }

  /**
   * Connection ping result.
   *
   * @param catalogCode current default datasource catalog code
   * @param tenantId resolved tenant id
   * @param contextId resolved permission context id
   * @param userId authenticated user id
   * @param loginSubject authenticated login subject
   * @param databaseProductName logical database product name
   * @param databaseProductVersion logical database product version
   * @param currentSchema current default schema
   */
  public record PingResult(
      String catalogCode,
      String tenantId,
      String contextId,
      String userId,
      String loginSubject,
      String databaseProductName,
      String databaseProductVersion,
      String currentSchema
  ) {
  }
}
