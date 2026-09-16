package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.trimToNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.federation.api.vo.FederationJdbcDriverModels;
import org.simplepoint.plugin.dna.federation.service.support.FederationJdbcMetadataSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Aggregates JDBC metadata across multiple federation data sources using
 * {@link FederationJdbcMetadataSupport}. Handles catalog filtering, parallel aggregation,
 * row deduplication and safe per-source failure isolation.
 */
@Service
class FederationJdbcMetadataQueryServiceImpl implements FederationJdbcMetadataQueryService {

  private static final Logger LOG = LoggerFactory.getLogger(FederationJdbcMetadataQueryServiceImpl.class);

  private static final String NO_MATCH_CATALOG_PATTERN = "__simplepoint_no_match__";

  private final FederationJdbcMetadataSupport jdbcMetadataSupport;

  /**
   * Creates the metadata query service.
   *
   * @param jdbcMetadataSupport low-level JDBC metadata support
   */
  FederationJdbcMetadataQueryServiceImpl(final FederationJdbcMetadataSupport jdbcMetadataSupport) {
    this.jdbcMetadataSupport = jdbcMetadataSupport;
  }

  @Override
  public FederationJdbcDriverModels.TabularResult catalogs(final List<JdbcDataSourceDefinition> dataSources) {
    return new FederationJdbcDriverModels.TabularResult(
        List.of(new FederationJdbcDriverModels.JdbcColumn("TABLE_CAT", "VARCHAR", java.sql.Types.VARCHAR)),
        (dataSources == null ? List.<JdbcDataSourceDefinition>of() : dataSources).stream()
            .map(JdbcDataSourceDefinition::getCode)
            .filter(Objects::nonNull)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .<List<Object>>map(code -> List.of(code))
            .toList()
    );
  }

  @Override
  public FederationJdbcDriverModels.TabularResult schemas(
      final List<JdbcDataSourceDefinition> dataSources,
      final String catalogPattern,
      final String schemaPattern
  ) {
    List<JdbcDataSourceDefinition> filtered = filterByCatalogPattern(dataSources, catalogPattern);
    if (filtered.isEmpty()) {
      List<JdbcDataSourceDefinition> all = dataSources == null ? List.of() : dataSources;
      if (all.isEmpty()) {
        return new FederationJdbcDriverModels.TabularResult(List.of(), List.of());
      }
      return jdbcMetadataSupport.schemas(all.get(0), NO_MATCH_CATALOG_PATTERN, schemaPattern);
    }
    return safeAggregateFromSources(filtered,
        ds -> jdbcMetadataSupport.schemas(ds, catalogPattern, schemaPattern));
  }

  @Override
  public FederationJdbcDriverModels.TabularResult tableTypes(final List<JdbcDataSourceDefinition> dataSources) {
    return deduplicateRows(mergeTabularResults(
        (dataSources == null ? List.<JdbcDataSourceDefinition>of() : dataSources).stream()
            .map(jdbcMetadataSupport::tableTypes)
            .toList()
    ));
  }

  @Override
  public FederationJdbcDriverModels.TabularResult tables(
      final List<JdbcDataSourceDefinition> dataSources,
      final String catalogPattern,
      final String schemaPattern,
      final String tablePattern,
      final List<String> types
  ) {
    List<JdbcDataSourceDefinition> filtered = filterByCatalogPattern(dataSources, catalogPattern);
    if (filtered.isEmpty()) {
      List<JdbcDataSourceDefinition> all = dataSources == null ? List.of() : dataSources;
      if (all.isEmpty()) {
        return new FederationJdbcDriverModels.TabularResult(List.of(), List.of());
      }
      return jdbcMetadataSupport.tables(all.get(0), NO_MATCH_CATALOG_PATTERN, schemaPattern, tablePattern, types);
    }
    return safeAggregateFromSources(filtered,
        ds -> jdbcMetadataSupport.tables(ds, catalogPattern, schemaPattern, tablePattern, types));
  }

  @Override
  public FederationJdbcDriverModels.TabularResult columns(
      final List<JdbcDataSourceDefinition> dataSources,
      final String catalogPattern,
      final String schemaPattern,
      final String tablePattern,
      final String columnPattern
  ) {
    List<JdbcDataSourceDefinition> filtered = filterByCatalogPattern(dataSources, catalogPattern);
    if (filtered.isEmpty()) {
      List<JdbcDataSourceDefinition> all = dataSources == null ? List.of() : dataSources;
      if (all.isEmpty()) {
        return new FederationJdbcDriverModels.TabularResult(List.of(), List.of());
      }
      return jdbcMetadataSupport.columns(all.get(0), NO_MATCH_CATALOG_PATTERN, schemaPattern, tablePattern, columnPattern);
    }
    return safeAggregateFromSources(filtered,
        ds -> jdbcMetadataSupport.columns(ds, catalogPattern, schemaPattern, tablePattern, columnPattern));
  }

  @Override
  public FederationJdbcDriverModels.TabularResult primaryKeys(
      final List<JdbcDataSourceDefinition> dataSources,
      final String catalog,
      final String schema,
      final String table
  ) {
    List<JdbcDataSourceDefinition> filtered = filterByCatalogPattern(dataSources, catalog);
    if (filtered.isEmpty()) {
      return new FederationJdbcDriverModels.TabularResult(List.of(), List.of());
    }
    return safeAggregateFromSources(filtered,
        ds -> jdbcMetadataSupport.primaryKeys(ds, catalog, schema, table));
  }

  @Override
  public FederationJdbcDriverModels.TabularResult indexInfo(
      final List<JdbcDataSourceDefinition> dataSources,
      final String catalog,
      final String schema,
      final String table,
      final boolean unique,
      final boolean approximate
  ) {
    List<JdbcDataSourceDefinition> filtered = filterByCatalogPattern(dataSources, catalog);
    if (filtered.isEmpty()) {
      return new FederationJdbcDriverModels.TabularResult(List.of(), List.of());
    }
    return safeAggregateFromSources(filtered,
        ds -> jdbcMetadataSupport.indexInfo(ds, catalog, schema, table, unique, approximate));
  }

  @Override
  public FederationJdbcDriverModels.TabularResult importedKeys(
      final List<JdbcDataSourceDefinition> dataSources,
      final String catalog,
      final String schema,
      final String table
  ) {
    List<JdbcDataSourceDefinition> filtered = filterByCatalogPattern(dataSources, catalog);
    if (filtered.isEmpty()) {
      return new FederationJdbcDriverModels.TabularResult(List.of(), List.of());
    }
    return safeAggregateFromSources(filtered,
        ds -> jdbcMetadataSupport.importedKeys(ds, catalog, schema, table));
  }

  @Override
  public FederationJdbcDriverModels.TabularResult exportedKeys(
      final List<JdbcDataSourceDefinition> dataSources,
      final String catalog,
      final String schema,
      final String table
  ) {
    List<JdbcDataSourceDefinition> filtered = filterByCatalogPattern(dataSources, catalog);
    if (filtered.isEmpty()) {
      return new FederationJdbcDriverModels.TabularResult(List.of(), List.of());
    }
    return safeAggregateFromSources(filtered,
        ds -> jdbcMetadataSupport.exportedKeys(ds, catalog, schema, table));
  }

  @Override
  public FederationJdbcDriverModels.TabularResult typeInfo(final List<JdbcDataSourceDefinition> dataSources) {
    return deduplicateRows(mergeTabularResults(
        (dataSources == null ? List.<JdbcDataSourceDefinition>of() : dataSources).stream()
            .map(jdbcMetadataSupport::typeInfo)
            .toList()
    ));
  }

  private FederationJdbcDriverModels.TabularResult safeAggregateFromSources(
      final List<JdbcDataSourceDefinition> sources,
      final DataSourceMetadataAction action
  ) {
    if (sources.size() == 1) {
      FederationJdbcDriverModels.TabularResult single = safeCollect(sources.get(0), action);
      return single != null ? single : new FederationJdbcDriverModels.TabularResult(List.of(), List.of());
    }
    return mergeTabularResults(sources.parallelStream()
        .map(source -> safeCollect(source, action))
        .filter(Objects::nonNull)
        .toList());
  }

  private FederationJdbcDriverModels.TabularResult safeCollect(
      final JdbcDataSourceDefinition dataSource,
      final DataSourceMetadataAction action
  ) {
    try {
      return action.apply(dataSource);
    } catch (Exception ex) {
      LOG.warn("数据源 [{}] 元数据查询失败，已跳过: {}", dataSource.getCode(), ex.getMessage(), ex);
      return null;
    }
  }

  static FederationJdbcDriverModels.TabularResult mergeTabularResults(
      final List<FederationJdbcDriverModels.TabularResult> results
  ) {
    LinkedHashMap<String, FederationJdbcDriverModels.JdbcColumn> mergedColumns = new LinkedHashMap<>();
    List<java.util.Map<String, Object>> mappedRows = new ArrayList<>();
    for (FederationJdbcDriverModels.TabularResult result : results == null
        ? List.<FederationJdbcDriverModels.TabularResult>of() : results) {
      if (result == null) {
        continue;
      }
      List<FederationJdbcDriverModels.JdbcColumn> columns = result.columns();
      for (FederationJdbcDriverModels.JdbcColumn column : columns) {
        if (column != null && trimToNull(column.name()) != null) {
          mergedColumns.putIfAbsent(column.name(), column);
        }
      }
      for (List<Object> row : result.rows()) {
        java.util.Map<String, Object> rowValues = new LinkedHashMap<>();
        for (int index = 0; index < columns.size(); index++) {
          FederationJdbcDriverModels.JdbcColumn column = columns.get(index);
          if (column == null || trimToNull(column.name()) == null) {
            continue;
          }
          rowValues.put(column.name(), row != null && index < row.size() ? row.get(index) : null);
        }
        mappedRows.add(rowValues);
      }
    }
    List<FederationJdbcDriverModels.JdbcColumn> columns = List.copyOf(mergedColumns.values());
    List<List<Object>> rows = mappedRows.stream()
        .map(row -> columns.stream()
            .map(column -> row.get(column.name()))
            .toList())
        .toList();
    return new FederationJdbcDriverModels.TabularResult(columns, rows);
  }

  static FederationJdbcDriverModels.TabularResult deduplicateRows(
      final FederationJdbcDriverModels.TabularResult result
  ) {
    if (result == null || result.rows().isEmpty()) {
      return result == null ? new FederationJdbcDriverModels.TabularResult(List.of(), List.of()) : result;
    }
    return new FederationJdbcDriverModels.TabularResult(result.columns(), result.rows().stream().distinct().toList());
  }

  static List<JdbcDataSourceDefinition> filterByCatalogPattern(
      final List<JdbcDataSourceDefinition> dataSources,
      final String catalog
  ) {
    String normalizedCatalog = trimToNull(catalog);
    if (normalizedCatalog == null) {
      return dataSources == null ? List.of() : dataSources;
    }
    return (dataSources == null ? List.<JdbcDataSourceDefinition>of() : dataSources).stream()
        .filter(ds -> normalizedCatalog.equalsIgnoreCase(ds.getCode()))
        .toList();
  }

  @FunctionalInterface
  private interface DataSourceMetadataAction {
    FederationJdbcDriverModels.TabularResult apply(JdbcDataSourceDefinition dataSource);
  }
}
