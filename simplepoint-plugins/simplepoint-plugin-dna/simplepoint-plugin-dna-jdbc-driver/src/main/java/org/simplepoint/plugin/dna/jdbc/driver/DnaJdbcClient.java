package org.simplepoint.plugin.dna.jdbc.driver;

import java.sql.SQLException;
import java.util.List;

/**
 * Transport facade used by the standalone DNA JDBC driver.
 */
final class DnaJdbcClient implements AutoCloseable {

  private final DnaJdbcSocketTransport transport;

  DnaJdbcClient(final DnaJdbcModels.ConnectionConfig config) throws SQLException {
    this.transport = new DnaJdbcSocketTransport(config);
  }

  DnaJdbcModels.PingResult ping() throws SQLException {
    return transport.ping();
  }

  DnaJdbcModels.TabularResult catalogs() throws SQLException {
    return transport.catalogs();
  }

  DnaJdbcModels.TabularResult schemas(final String catalogPattern, final String schemaPattern) throws SQLException {
    return transport.schemas(catalogPattern, schemaPattern);
  }

  DnaJdbcModels.TabularResult tableTypes() throws SQLException {
    return transport.tableTypes();
  }

  DnaJdbcModels.TabularResult tables(
      final String catalogPattern,
      final String schemaPattern,
      final String tablePattern,
      final List<String> types
  ) throws SQLException {
    return transport.tables(catalogPattern, schemaPattern, tablePattern, types);
  }

  DnaJdbcModels.TabularResult columns(
      final String catalogPattern,
      final String schemaPattern,
      final String tablePattern,
      final String columnPattern
  ) throws SQLException {
    return transport.columns(catalogPattern, schemaPattern, tablePattern, columnPattern);
  }

  DnaJdbcModels.TabularResult primaryKeys(
      final String catalog,
      final String schema,
      final String table
  ) throws SQLException {
    return transport.primaryKeys(catalog, schema, table);
  }

  DnaJdbcModels.TabularResult indexInfo(
      final String catalog,
      final String schema,
      final String table,
      final boolean unique,
      final boolean approximate
  ) throws SQLException {
    return transport.indexInfo(catalog, schema, table, unique, approximate);
  }

  DnaJdbcModels.TabularResult importedKeys(
      final String catalog,
      final String schema,
      final String table
  ) throws SQLException {
    return transport.importedKeys(catalog, schema, table);
  }

  DnaJdbcModels.TabularResult exportedKeys(
      final String catalog,
      final String schema,
      final String table
  ) throws SQLException {
    return transport.exportedKeys(catalog, schema, table);
  }

  DnaJdbcModels.TabularResult typeInfo() throws SQLException {
    return transport.typeInfo();
  }

  DnaJdbcModels.QueryResult query(
      final String catalogCode,
      final String sql,
      final String defaultSchema
  ) throws SQLException {
    return transport.query(catalogCode, sql, defaultSchema);
  }

  DnaJdbcModels.UpdateResult executeUpdate(
      final String catalogCode,
      final String sql,
      final String defaultSchema
  ) throws SQLException {
    return transport.executeUpdate(catalogCode, sql, defaultSchema);
  }

  DnaJdbcModels.UpdateResult executeDdl(
      final String catalogCode,
      final String sql,
      final String defaultSchema
  ) throws SQLException {
    return transport.executeDdl(catalogCode, sql, defaultSchema);
  }

  void flushCache() throws SQLException {
    transport.flushCache();
  }

  void setSocketTimeout(final int timeoutMs) throws SQLException {
    transport.setSocketTimeout(timeoutMs);
  }

  @Override
  public void close() throws SQLException {
    transport.close();
  }
}
