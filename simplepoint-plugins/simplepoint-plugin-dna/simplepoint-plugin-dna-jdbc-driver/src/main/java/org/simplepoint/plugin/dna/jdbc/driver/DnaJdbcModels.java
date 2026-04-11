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
      Boolean approximate
  ) {
  }

  record SocketResponse(
      Boolean success,
      String errorMessage,
      PingResult pingResult,
      TabularResult tabularResult,
      QueryResult queryResult
  ) {
  }
}
