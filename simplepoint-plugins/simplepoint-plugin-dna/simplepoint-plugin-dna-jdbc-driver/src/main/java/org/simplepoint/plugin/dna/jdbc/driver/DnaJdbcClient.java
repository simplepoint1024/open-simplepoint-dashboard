package org.simplepoint.plugin.dna.jdbc.driver;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Transport facade used by the standalone DNA JDBC driver.
 * Caches metadata results to avoid redundant TCP round-trips during IDE refresh cycles.
 */
final class DnaJdbcClient implements AutoCloseable {

  private final DnaJdbcSocketTransport transport;

  private final ConcurrentMap<String, DnaJdbcModels.TabularResult> metadataCache;

  DnaJdbcClient(final DnaJdbcModels.ConnectionConfig config) throws SQLException {
    this.transport = new DnaJdbcSocketTransport(config);
    this.metadataCache = new ConcurrentHashMap<>();
  }

  DnaJdbcModels.PingResult ping() throws SQLException {
    return transport.ping();
  }

  DnaJdbcModels.TabularResult catalogs() throws SQLException {
    return cachedMetadata("catalogs", () -> transport.catalogs());
  }

  DnaJdbcModels.TabularResult schemas(final String catalogPattern, final String schemaPattern) throws SQLException {
    return cachedMetadata("schemas:" + norm(catalogPattern) + ':' + norm(schemaPattern),
        () -> transport.schemas(catalogPattern, schemaPattern));
  }

  DnaJdbcModels.TabularResult tableTypes() throws SQLException {
    return cachedMetadata("tableTypes", () -> transport.tableTypes());
  }

  DnaJdbcModels.TabularResult tables(
      final String catalogPattern,
      final String schemaPattern,
      final String tablePattern,
      final List<String> types
  ) throws SQLException {
    return cachedMetadata("tables:" + norm(catalogPattern) + ':' + norm(schemaPattern)
            + ':' + norm(tablePattern) + ':' + (types == null ? "*" : types),
        () -> transport.tables(catalogPattern, schemaPattern, tablePattern, types));
  }

  DnaJdbcModels.TabularResult columns(
      final String catalogPattern,
      final String schemaPattern,
      final String tablePattern,
      final String columnPattern
  ) throws SQLException {
    return cachedMetadata("columns:" + norm(catalogPattern) + ':' + norm(schemaPattern)
            + ':' + norm(tablePattern) + ':' + norm(columnPattern),
        () -> transport.columns(catalogPattern, schemaPattern, tablePattern, columnPattern));
  }

  DnaJdbcModels.TabularResult primaryKeys(
      final String catalog,
      final String schema,
      final String table
  ) throws SQLException {
    return cachedMetadata("primaryKeys:" + norm(catalog) + ':' + norm(schema) + ':' + norm(table),
        () -> transport.primaryKeys(catalog, schema, table));
  }

  DnaJdbcModels.TabularResult indexInfo(
      final String catalog,
      final String schema,
      final String table,
      final boolean unique,
      final boolean approximate
  ) throws SQLException {
    return cachedMetadata("indexInfo:" + norm(catalog) + ':' + norm(schema) + ':' + norm(table)
            + ':' + unique + ':' + approximate,
        () -> transport.indexInfo(catalog, schema, table, unique, approximate));
  }

  DnaJdbcModels.TabularResult importedKeys(
      final String catalog,
      final String schema,
      final String table
  ) throws SQLException {
    return cachedMetadata("importedKeys:" + norm(catalog) + ':' + norm(schema) + ':' + norm(table),
        () -> transport.importedKeys(catalog, schema, table));
  }

  DnaJdbcModels.TabularResult exportedKeys(
      final String catalog,
      final String schema,
      final String table
  ) throws SQLException {
    return cachedMetadata("exportedKeys:" + norm(catalog) + ':' + norm(schema) + ':' + norm(table),
        () -> transport.exportedKeys(catalog, schema, table));
  }

  DnaJdbcModels.TabularResult typeInfo() throws SQLException {
    return cachedMetadata("typeInfo", () -> transport.typeInfo());
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
    metadataCache.clear();
    transport.flushCache();
  }

  void setSocketTimeout(final int timeoutMs) throws SQLException {
    transport.setSocketTimeout(timeoutMs);
  }

  @Override
  public void close() throws SQLException {
    metadataCache.clear();
    transport.close();
  }

  private DnaJdbcModels.TabularResult cachedMetadata(
      final String key,
      final MetadataSupplier supplier
  ) throws SQLException {
    DnaJdbcModels.TabularResult cached = metadataCache.get(key);
    if (cached != null) {
      return cached;
    }
    DnaJdbcModels.TabularResult result = supplier.get();
    if (result != null) {
      metadataCache.put(key, result);
    }
    return result;
  }

  private static String norm(final String value) {
    return value == null || value.isBlank() ? "*" : value;
  }

  @FunctionalInterface
  private interface MetadataSupplier {
    DnaJdbcModels.TabularResult get() throws SQLException;
  }
}
