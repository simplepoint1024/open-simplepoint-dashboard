package org.simplepoint.plugin.dna.jdbc.driver;

import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Transport facade used by the standalone DNA JDBC driver.
 * Caches metadata results to avoid redundant TCP round-trips during IDE refresh cycles.
 * Cached entries expire after a configurable TTL (default 300 s) so that schema
 * changes are picked up without a manual cache flush.
 */
final class DnaJdbcClient implements AutoCloseable {

  private static final long DEFAULT_CACHE_TTL_SECONDS = 300;

  private final DnaJdbcSocketTransport transport;

  private final ConcurrentMap<String, TimedEntry> metadataCache;

  private final long cacheTtlMillis;

  DnaJdbcClient(final DnaJdbcModels.ConnectionConfig config) throws SQLException {
    this.transport = new DnaJdbcSocketTransport(config);
    this.metadataCache = new ConcurrentHashMap<>();
    this.cacheTtlMillis = resolveCacheTtl(config) * 1_000L;
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
      final String defaultSchema,
      final List<Object> parameters,
      final Integer maxRows
  ) throws SQLException {
    return transport.query(catalogCode, sql, defaultSchema, parameters, maxRows);
  }

  DnaJdbcModels.UpdateResult executeUpdate(
      final String catalogCode,
      final String sql,
      final String defaultSchema,
      final List<Object> parameters
  ) throws SQLException {
    return transport.executeUpdate(catalogCode, sql, defaultSchema, parameters);
  }

  DnaJdbcModels.UpdateResult executeDdl(
      final String catalogCode,
      final String sql,
      final String defaultSchema,
      final List<Object> parameters
  ) throws SQLException {
    return transport.executeDdl(catalogCode, sql, defaultSchema, parameters);
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
    long now = System.currentTimeMillis();
    evictExpired(now);
    TimedEntry entry = metadataCache.get(key);
    if (entry != null && now - entry.timestamp < cacheTtlMillis) {
      return entry.value;
    }
    DnaJdbcModels.TabularResult result = supplier.get();
    if (result != null) {
      metadataCache.put(key, new TimedEntry(result, now));
    }
    return result;
  }

  private void evictExpired(final long now) {
    Iterator<Map.Entry<String, TimedEntry>> it = metadataCache.entrySet().iterator();
    while (it.hasNext()) {
      if (now - it.next().getValue().timestamp >= cacheTtlMillis) {
        it.remove();
      }
    }
  }

  private static long resolveCacheTtl(final DnaJdbcModels.ConnectionConfig config) {
    if (config.properties() == null) {
      return DEFAULT_CACHE_TTL_SECONDS;
    }
    String value = config.properties().getProperty("metadataCacheTtlSeconds");
    if (value == null || value.isBlank()) {
      return DEFAULT_CACHE_TTL_SECONDS;
    }
    try {
      long ttl = Long.parseLong(value.trim());
      return ttl > 0 ? ttl : DEFAULT_CACHE_TTL_SECONDS;
    } catch (NumberFormatException e) {
      return DEFAULT_CACHE_TTL_SECONDS;
    }
  }

  private static String norm(final String value) {
    return value == null || value.isBlank() ? "*" : value;
  }

  private record TimedEntry(DnaJdbcModels.TabularResult value, long timestamp) {
  }

  @FunctionalInterface
  private interface MetadataSupplier {
    DnaJdbcModels.TabularResult get() throws SQLException;
  }
}
