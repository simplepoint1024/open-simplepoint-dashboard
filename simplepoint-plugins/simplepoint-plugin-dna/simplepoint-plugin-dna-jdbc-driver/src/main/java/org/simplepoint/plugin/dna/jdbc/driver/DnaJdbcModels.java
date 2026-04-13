package org.simplepoint.plugin.dna.jdbc.driver;

import java.net.URI;
import java.util.List;

/**
 * Client-side models used by the standalone DNA JDBC driver.
 */
final class DnaJdbcModels {

  private DnaJdbcModels() {
  }

  record ConnectionConfig(
      URI baseUri,
      String originalUrl,
      String loginSubject,
      String password,
      String catalogCode,
      String tenantId,
      String contextId,
      String schema,
      java.util.Properties properties
  ) {
  }

  record ColumnDef(
      String name,
      String typeName,
      Integer jdbcType
  ) {
  }

  record PingResult(
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

  record TabularResult(
      List<ColumnDef> columns,
      List<List<Object>> rows
  ) {
  }

  record QueryRequest(
      String sql,
      String defaultSchema,
      String catalogCode
  ) {
  }

  record QueryResult(
      List<ColumnDef> columns,
      List<List<Object>> rows,
      Boolean truncated,
      Long returnedRows
  ) {
  }

  record UpdateResult(
      String catalogCode,
      String dataSourceCode,
      Long affectedRows,
      Long executionTimeMs,
      String pushedSql
  ) {
  }

  record SocketRequest(
      String action,
      String loginSubject,
      String password,
      String catalogCode,
      String tenantId,
      String contextId,
      String schema,
      String catalogPattern,
      String schemaPattern,
      String tablePattern,
      String columnPattern,
      List<String> types,
      String sql,
      String defaultSchema,
      Boolean unique,
      Boolean approximate,
      List<SocketRequest> batch,
      List<Object> parameters
  ) {

    static Builder builder(final String action) {
      return new Builder(action);
    }

    static final class Builder {

      private final String action;
      private String loginSubject;
      private String password;
      private String catalogCode;
      private String tenantId;
      private String contextId;
      private String schema;
      private String catalogPattern;
      private String schemaPattern;
      private String tablePattern;
      private String columnPattern;
      private List<String> types;
      private String sql;
      private String defaultSchema;
      private Boolean unique;
      private Boolean approximate;
      private List<SocketRequest> batch;
      private List<Object> parameters;

      private Builder(final String action) {
        this.action = action;
      }

      Builder loginSubject(final String value) {
        this.loginSubject = value;
        return this;
      }

      Builder password(final String value) {
        this.password = value;
        return this;
      }

      Builder catalogCode(final String value) {
        this.catalogCode = value;
        return this;
      }

      Builder tenantId(final String value) {
        this.tenantId = value;
        return this;
      }

      Builder contextId(final String value) {
        this.contextId = value;
        return this;
      }

      Builder schema(final String value) {
        this.schema = value;
        return this;
      }

      Builder catalogPattern(final String value) {
        this.catalogPattern = value;
        return this;
      }

      Builder schemaPattern(final String value) {
        this.schemaPattern = value;
        return this;
      }

      Builder tablePattern(final String value) {
        this.tablePattern = value;
        return this;
      }

      Builder columnPattern(final String value) {
        this.columnPattern = value;
        return this;
      }

      Builder types(final List<String> value) {
        this.types = value;
        return this;
      }

      Builder sql(final String value) {
        this.sql = value;
        return this;
      }

      Builder defaultSchema(final String value) {
        this.defaultSchema = value;
        return this;
      }

      Builder unique(final Boolean value) {
        this.unique = value;
        return this;
      }

      Builder approximate(final Boolean value) {
        this.approximate = value;
        return this;
      }

      Builder batch(final List<SocketRequest> value) {
        this.batch = value;
        return this;
      }

      Builder parameters(final List<Object> value) {
        this.parameters = value;
        return this;
      }

      SocketRequest build() {
        return new SocketRequest(
            action, loginSubject, password, catalogCode, tenantId, contextId,
            schema, catalogPattern, schemaPattern, tablePattern, columnPattern,
            types, sql, defaultSchema, unique, approximate, batch, parameters
        );
      }
    }
  }

  record SocketResponse(
      Boolean success,
      String errorMessage,
      PingResult pingResult,
      TabularResult tabularResult,
      QueryResult queryResult,
      UpdateResult updateResult,
      List<SocketResponse> batchResults
  ) {
  }
}
