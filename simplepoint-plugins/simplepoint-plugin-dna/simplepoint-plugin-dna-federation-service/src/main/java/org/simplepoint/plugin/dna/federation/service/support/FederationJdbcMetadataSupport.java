package org.simplepoint.plugin.dna.federation.service.support;

import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.requireValue;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.trimToNull;

import java.io.IOException;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;
import org.simplepoint.plugin.dna.core.api.repository.JdbcDriverDefinitionRepository;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.core.api.service.JdbcDialectManagementService;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.federation.api.vo.FederationJdbcDriverModels;
import org.springframework.stereotype.Component;

/**
 * Resolves JDBC metadata directly from one physical datasource for the external DNA JDBC driver.
 */
@Component
public class FederationJdbcMetadataSupport {

  private static final String NO_MATCH_PATTERN = "__simplepoint_no_match__";

  private final JdbcDataSourceDefinitionService dataSourceService;

  private final JdbcDriverDefinitionRepository driverRepository;

  private final JdbcDialectManagementService dialectManagementService;

  /**
   * Creates the metadata support.
   *
   * @param dataSourceService datasource service
   * @param driverRepository driver repository
   * @param dialectManagementService dialect service
   */
  public FederationJdbcMetadataSupport(
      final JdbcDataSourceDefinitionService dataSourceService,
      final JdbcDriverDefinitionRepository driverRepository,
      final JdbcDialectManagementService dialectManagementService
  ) {
    this.dataSourceService = dataSourceService;
    this.driverRepository = driverRepository;
    this.dialectManagementService = dialectManagementService;
  }

  /**
   * Returns the datasource itself as the only exposed JDBC catalog.
   *
   * @param dataSource datasource definition
   * @return catalog metadata
   */
  public FederationJdbcDriverModels.TabularResult catalogs(final JdbcDataSourceDefinition dataSource) {
    return new FederationJdbcDriverModels.TabularResult(
        List.of(new FederationJdbcDriverModels.JdbcColumn("TABLE_CAT", "VARCHAR", java.sql.Types.VARCHAR)),
        List.of(List.of(requireDataSourceCode(dataSource)))
    );
  }

  /**
   * Returns flattened schemas for the supplied datasource.
   *
   * @param dataSource datasource definition
   * @param catalogPattern optional catalog pattern
   * @param schemaPattern optional schema pattern
   * @return schema metadata
   */
  public FederationJdbcDriverModels.TabularResult schemas(
      final JdbcDataSourceDefinition dataSource,
      final String catalogPattern,
      final String schemaPattern
  ) {
    if (!matchesCatalogPattern(dataSource, catalogPattern)) {
      return emptySchemasResult();
    }
    String dataSourceCode = requireDataSourceCode(dataSource);
    List<SchemaTarget> targets = filterSchemaTargets(listSchemaTargets(dataSource), schemaPattern);
    List<List<Object>> rows = targets.stream()
        .filter(target -> trimToNull(target.schemaLabel()) != null)
        .sorted(Comparator.comparing(SchemaTarget::schemaLabel, String.CASE_INSENSITIVE_ORDER))
        .<List<Object>>map(target -> List.of(target.schemaLabel(), dataSourceCode))
        .toList();
    return new FederationJdbcDriverModels.TabularResult(
        List.of(
            new FederationJdbcDriverModels.JdbcColumn("TABLE_SCHEM", "VARCHAR", java.sql.Types.VARCHAR),
            new FederationJdbcDriverModels.JdbcColumn("TABLE_CATALOG", "VARCHAR", java.sql.Types.VARCHAR)
        ),
        rows
    );
  }

  /**
   * Returns table types supported by the datasource.
   *
   * @param dataSource datasource definition
   * @return table-type metadata
   */
  public FederationJdbcDriverModels.TabularResult tableTypes(final JdbcDataSourceDefinition dataSource) {
    return withContext(dataSource, null, context -> toTabularResult(
        context.dialect().loadTableTypes(
            context.connection(),
            context.metaData(),
            context.supportContext()
        )
    ));
  }

  /**
   * Returns tables and views under the flattened schema model.
   *
   * @param dataSource datasource definition
   * @param catalogPattern optional catalog pattern
   * @param schemaPattern optional schema pattern
   * @param tablePattern optional table pattern
   * @param types optional table types
   * @return table metadata
   */
  public FederationJdbcDriverModels.TabularResult tables(
      final JdbcDataSourceDefinition dataSource,
      final String catalogPattern,
      final String schemaPattern,
      final String tablePattern,
      final List<String> types
  ) {
    if (!matchesCatalogPattern(dataSource, catalogPattern)) {
      return emptyTablesResult(dataSource, tablePattern, types);
    }
    TabularResultAccumulator accumulator = new TabularResultAccumulator();
    List<SchemaTarget> targets = filterSchemaTargets(listSchemaTargets(dataSource), schemaPattern);
    if (targets.isEmpty()) {
      return emptyTablesResult(dataSource, tablePattern, types);
    }
    for (SchemaTarget target : targets) {
      withContext(dataSource, target.catalog(), context -> {
        JdbcDatabaseDialect.MetadataResult metadata = context.dialect().loadTables(
            context.connection(),
            context.metaData(),
            context.supportContext(),
            resolveMetadataCatalog(target, context),
            resolveMetadataSchema(target, context),
            trimToNull(tablePattern) == null ? "%" : tablePattern,
            types
        );
        List<FederationJdbcDriverModels.JdbcColumn> columns = toColumns(metadata.columns());
        accumulator.captureColumns(columns);
        for (List<Object> row : metadata.rows()) {
          Map<String, Object> overrides = new LinkedHashMap<>();
          overrides.put("TABLE_CAT", requireDataSourceCode(dataSource));
          overrides.put("TABLE_SCHEM", target.schemaLabel());
          accumulator.addRow(columns, row, overrides);
        }
        return null;
      });
    }
    return accumulator.build();
  }

  /**
   * Returns columns under the flattened schema model.
   *
   * @param dataSource datasource definition
   * @param catalogPattern optional catalog pattern
   * @param schemaPattern optional schema pattern
   * @param tablePattern optional table pattern
   * @param columnPattern optional column pattern
   * @return column metadata
   */
  public FederationJdbcDriverModels.TabularResult columns(
      final JdbcDataSourceDefinition dataSource,
      final String catalogPattern,
      final String schemaPattern,
      final String tablePattern,
      final String columnPattern
  ) {
    if (!matchesCatalogPattern(dataSource, catalogPattern)) {
      return emptyColumnsResult(dataSource, tablePattern, columnPattern);
    }
    TabularResultAccumulator accumulator = new TabularResultAccumulator();
    List<SchemaTarget> targets = filterSchemaTargets(listSchemaTargets(dataSource), schemaPattern);
    if (targets.isEmpty()) {
      return emptyColumnsResult(dataSource, tablePattern, columnPattern);
    }
    for (SchemaTarget target : targets) {
      withContext(dataSource, target.catalog(), context -> {
        JdbcDatabaseDialect.MetadataResult metadata = context.dialect().loadColumns(
            context.connection(),
            context.metaData(),
            context.supportContext(),
            resolveMetadataCatalog(target, context),
            resolveMetadataSchema(target, context),
            trimToNull(tablePattern) == null ? "%" : tablePattern,
            trimToNull(columnPattern) == null ? "%" : columnPattern
        );
        List<FederationJdbcDriverModels.JdbcColumn> columns = toColumns(metadata.columns());
        accumulator.captureColumns(columns);
        for (List<Object> row : metadata.rows()) {
          accumulator.addRow(columns, row, Map.of(
              "TABLE_CAT", requireDataSourceCode(dataSource),
              "TABLE_SCHEM", target.schemaLabel()
          ));
        }
        return null;
      });
    }
    return accumulator.build();
  }

  /**
   * Returns primary keys for the specified table.
   *
   * @param dataSource datasource definition
   * @param catalog optional catalog name (exact, or null for all)
   * @param schema optional schema name (exact, or null)
   * @param table table name
   * @return primary key metadata
   */
  public FederationJdbcDriverModels.TabularResult primaryKeys(
      final JdbcDataSourceDefinition dataSource,
      final String catalog,
      final String schema,
      final String table
  ) {
    return tableMetadata(dataSource, catalog, schema,
        (context, target) -> context.metaData().getPrimaryKeys(
            resolveMetadataCatalog(target, context),
            resolveMetadataSchema(target, context),
            table
        )
    );
  }

  /**
   * Returns index information for the specified table.
   *
   * @param dataSource datasource definition
   * @param catalog optional catalog name
   * @param schema optional schema name
   * @param table table name
   * @param unique only unique indexes
   * @param approximate allow approximate results
   * @return index metadata
   */
  public FederationJdbcDriverModels.TabularResult indexInfo(
      final JdbcDataSourceDefinition dataSource,
      final String catalog,
      final String schema,
      final String table,
      final boolean unique,
      final boolean approximate
  ) {
    return tableMetadata(dataSource, catalog, schema,
        (context, target) -> context.metaData().getIndexInfo(
            resolveMetadataCatalog(target, context),
            resolveMetadataSchema(target, context),
            table,
            unique,
            approximate
        )
    );
  }

  /**
   * Returns imported (foreign) keys for the specified table.
   *
   * @param dataSource datasource definition
   * @param catalog optional catalog name
   * @param schema optional schema name
   * @param table table name
   * @return imported key metadata
   */
  public FederationJdbcDriverModels.TabularResult importedKeys(
      final JdbcDataSourceDefinition dataSource,
      final String catalog,
      final String schema,
      final String table
  ) {
    return tableMetadata(dataSource, catalog, schema,
        (context, target) -> context.metaData().getImportedKeys(
            resolveMetadataCatalog(target, context),
            resolveMetadataSchema(target, context),
            table
        )
    );
  }

  /**
   * Returns exported (foreign) keys for the specified table.
   *
   * @param dataSource datasource definition
   * @param catalog optional catalog name
   * @param schema optional schema name
   * @param table table name
   * @return exported key metadata
   */
  public FederationJdbcDriverModels.TabularResult exportedKeys(
      final JdbcDataSourceDefinition dataSource,
      final String catalog,
      final String schema,
      final String table
  ) {
    return tableMetadata(dataSource, catalog, schema,
        (context, target) -> context.metaData().getExportedKeys(
            resolveMetadataCatalog(target, context),
            resolveMetadataSchema(target, context),
            table
        )
    );
  }

  /**
   * Returns type information for the datasource.
   *
   * @param dataSource datasource definition
   * @return type metadata
   */
  public FederationJdbcDriverModels.TabularResult typeInfo(final JdbcDataSourceDefinition dataSource) {
    return withContext(dataSource, null, context -> {
      try (ResultSet rs = context.metaData().getTypeInfo()) {
        return resultSetToTabularResult(rs, Map.of());
      }
    });
  }

  /**
   * Generic helper for table-scoped metadata that resolves the catalog/schema target and converts
   * the raw JDBC ResultSet into a TabularResult with overridden TABLE_CAT/TABLE_SCHEM.
   */
  private FederationJdbcDriverModels.TabularResult tableMetadata(
      final JdbcDataSourceDefinition dataSource,
      final String catalog,
      final String schema,
      final MetadataQuery query
  ) {
    TabularResultAccumulator accumulator = new TabularResultAccumulator();
    List<SchemaTarget> targets;
    if (trimToNull(catalog) != null && !matchesCatalogPattern(dataSource, catalog)) {
      return new FederationJdbcDriverModels.TabularResult(List.of(), List.of());
    }
    if (trimToNull(schema) != null) {
      targets = filterSchemaTargets(listSchemaTargets(dataSource), schema);
    } else {
      targets = listSchemaTargets(dataSource);
    }
    if (targets.isEmpty()) {
      return new FederationJdbcDriverModels.TabularResult(List.of(), List.of());
    }
    String dsCode = requireDataSourceCode(dataSource);
    for (SchemaTarget target : targets) {
      withContext(dataSource, target.catalog(), context -> {
        try (ResultSet rs = query.execute(context, target)) {
          FederationJdbcDriverModels.TabularResult partial = resultSetToTabularResult(rs, Map.of(
              "TABLE_CAT", dsCode,
              "TABLE_SCHEM", target.schemaLabel()
          ));
          accumulator.captureColumns(partial.columns());
          accumulator.addAllRows(partial.rows());
        }
        return null;
      });
    }
    return accumulator.build();
  }

  private static FederationJdbcDriverModels.TabularResult resultSetToTabularResult(
      final ResultSet rs,
      final Map<String, Object> overrides
  ) throws SQLException {
    ResultSetMetaData rsmd = rs.getMetaData();
    int columnCount = rsmd.getColumnCount();
    List<FederationJdbcDriverModels.JdbcColumn> columns = new ArrayList<>(columnCount);
    Map<String, Object> normalizedOverrides = new LinkedHashMap<>();
    overrides.forEach((k, v) -> normalizedOverrides.put(k == null ? "" : k.toUpperCase(Locale.ROOT), v));
    for (int i = 1; i <= columnCount; i++) {
      columns.add(new FederationJdbcDriverModels.JdbcColumn(
          rsmd.getColumnName(i),
          rsmd.getColumnTypeName(i),
          rsmd.getColumnType(i)
      ));
    }
    List<List<Object>> rows = new ArrayList<>();
    while (rs.next()) {
      List<Object> row = new ArrayList<>(columnCount);
      for (int i = 1; i <= columnCount; i++) {
        String colName = rsmd.getColumnName(i).toUpperCase(Locale.ROOT);
        if (normalizedOverrides.containsKey(colName)) {
          row.add(normalizedOverrides.get(colName));
        } else {
          row.add(rs.getObject(i));
        }
      }
      rows.add(row);
    }
    return new FederationJdbcDriverModels.TabularResult(columns, rows);
  }

  @FunctionalInterface
  private interface MetadataQuery {
    ResultSet execute(RuntimeContext context, SchemaTarget target) throws SQLException;
  }

  private static FederationJdbcDriverModels.TabularResult emptySchemasResult() {
    return new FederationJdbcDriverModels.TabularResult(
        List.of(
            new FederationJdbcDriverModels.JdbcColumn("TABLE_SCHEM", "VARCHAR", java.sql.Types.VARCHAR),
            new FederationJdbcDriverModels.JdbcColumn("TABLE_CATALOG", "VARCHAR", java.sql.Types.VARCHAR)
        ),
        List.of()
    );
  }

  private FederationJdbcDriverModels.TabularResult emptyTablesResult(
      final JdbcDataSourceDefinition dataSource,
      final String tablePattern,
      final List<String> types
  ) {
    return withContext(dataSource, null, context -> new FederationJdbcDriverModels.TabularResult(
        toColumns(context.dialect().loadTables(
            context.connection(),
            context.metaData(),
            context.supportContext(),
            context.supportContext().currentCatalog(),
            context.supportContext().currentSchema(),
            trimToNull(tablePattern) == null ? NO_MATCH_PATTERN : tablePattern,
            types
        ).columns()),
        List.of()
    ));
  }

  private FederationJdbcDriverModels.TabularResult emptyColumnsResult(
      final JdbcDataSourceDefinition dataSource,
      final String tablePattern,
      final String columnPattern
  ) {
    return withContext(dataSource, null, context -> new FederationJdbcDriverModels.TabularResult(
        toColumns(context.dialect().loadColumns(
            context.connection(),
            context.metaData(),
            context.supportContext(),
            context.supportContext().currentCatalog(),
            context.supportContext().currentSchema(),
            trimToNull(tablePattern) == null ? NO_MATCH_PATTERN : tablePattern,
            trimToNull(columnPattern) == null ? NO_MATCH_PATTERN : columnPattern
        ).columns()),
        List.of()
    ));
  }

  private List<SchemaTarget> listSchemaTargets(final JdbcDataSourceDefinition dataSource) {
    return withContext(dataSource, null, context -> {
      JdbcDatabaseDialect.MetadataNamespaceSupport namespaceSupport = context.dialect().resolveMetadataNamespaceSupport(
          context.connection(),
          context.metaData(),
          context.supportContext()
      );

      if (namespaceSupport.supportsSchemasInTableDefinitions()) {
        List<String> visibleSchemas = extractStringValues(
            context.dialect().loadSchemas(
                context.connection(),
                context.metaData(),
                context.supportContext(),
                context.supportContext().currentCatalog(),
                null
            ),
            "TABLE_SCHEM"
        );
        if (!visibleSchemas.isEmpty()) {
          return visibleSchemas.stream()
              .map(schema -> trimToNull(schema))
              .filter(Objects::nonNull)
              .map(schema -> new SchemaTarget(
                  schema,
                  context.supportContext().currentCatalog(),
                  schema
              ))
              .sorted(Comparator.comparing(SchemaTarget::schemaLabel, String.CASE_INSENSITIVE_ORDER))
              .toList();
        }
      }

      if (namespaceSupport.supportsCatalogsInTableDefinitions() && !namespaceSupport.supportsSchemasInTableDefinitions()) {
        List<String> visibleCatalogs = extractStringValues(
            context.dialect().loadCatalogs(
                context.connection(),
                context.metaData(),
                context.supportContext(),
                null
            ),
            "TABLE_CAT"
        );
        if (!visibleCatalogs.isEmpty()) {
          return visibleCatalogs.stream()
              .map(catalog -> trimToNull(catalog))
              .filter(Objects::nonNull)
              .map(catalog -> new SchemaTarget(catalog, catalog, null))
              .sorted(Comparator.comparing(SchemaTarget::schemaLabel, String.CASE_INSENSITIVE_ORDER))
              .toList();
        }
      }

      String currentCatalog = trimToNull(context.supportContext().currentCatalog());
      String currentSchema = trimToNull(context.supportContext().currentSchema());
      String fallbackLabel = currentSchema != null ? currentSchema
          : currentCatalog != null ? currentCatalog : null;
      return List.of(new SchemaTarget(fallbackLabel, currentCatalog, currentSchema));
    });
  }

  private static boolean matchesCatalogPattern(
      final JdbcDataSourceDefinition dataSource,
      final String catalog
  ) {
    String normalizedCatalog = trimToNull(catalog);
    if (normalizedCatalog == null) {
      return true;
    }
    return normalizedCatalog.equalsIgnoreCase(requireDataSourceCode(dataSource));
  }

  private static List<SchemaTarget> filterSchemaTargets(
      final List<SchemaTarget> targets,
      final String schemaPattern
  ) {
    String normalizedPattern = trimToNull(schemaPattern);
    if (normalizedPattern == null) {
      return targets == null ? List.of() : List.copyOf(targets);
    }
    List<SchemaTarget> directMatches = (targets == null ? List.<SchemaTarget>of() : targets).stream()
        .filter(target -> matchesPattern(target.schemaLabel(), normalizedPattern))
        .toList();
    if (!directMatches.isEmpty()) {
      return directMatches;
    }
    return (targets == null ? List.<SchemaTarget>of() : targets).stream()
        .filter(target -> matchesPattern(target.schema(), normalizedPattern)
            || matchesPattern(target.catalog(), normalizedPattern))
        .toList();
  }

  private <T> T withContext(
      final JdbcDataSourceDefinition dataSource,
      final String targetCatalog,
      final SqlCallback<T> callback
  ) {
    if (dataSource == null) {
      throw new IllegalArgumentException("数据源不能为空");
    }
    JdbcDriverDefinition driver = driverRepository.findActiveById(requireValue(
        dataSource.getDriverId(),
        "数据源驱动ID不能为空"
    )).orElseThrow(() -> new IllegalArgumentException("驱动不存在: " + dataSource.getDriverId()));
    String dataSourceId = requireValue(dataSource.getId(), "数据源ID不能为空");
    try (Connection baseConnection = dataSourceService.requireSimpleDataSource(dataSourceId).getConnection()) {
      RuntimeContext runtimeContext = createRuntimeContext(baseConnection, driver, dataSource);
      String normalizedTargetCatalog = trimToNull(targetCatalog);
      if (normalizedTargetCatalog != null
          && runtimeContext.dialect().requiresCatalogConnection(normalizedTargetCatalog, runtimeContext.supportContext())) {
        String targetJdbcUrl = runtimeContext.dialect().remapJdbcUrlCatalog(
            dataSource.getJdbcUrl(),
            normalizedTargetCatalog,
            runtimeContext.supportContext()
        );
        SimpleDataSource transientDataSource = dataSourceService.createTransientSimpleDataSource(dataSourceId, targetJdbcUrl);
        try (Connection targetConnection = transientDataSource.getConnection()) {
          return callback.execute(createRuntimeContext(targetConnection, driver, dataSource));
        } finally {
          transientDataSource.close();
        }
      }
      return callback.execute(runtimeContext);
    } catch (SQLException ex) {
      throw new IllegalStateException("访问 JDBC 元数据失败: " + rootMessage(ex), ex);
    }
  }

  private RuntimeContext createRuntimeContext(
      final Connection connection,
      final JdbcDriverDefinition driver,
      final JdbcDataSourceDefinition dataSource
  ) throws SQLException {
    DatabaseMetaData metaData = connection.getMetaData();
    JdbcDatabaseDialect.SupportContext supportContext = new JdbcDatabaseDialect.SupportContext(
        driver.getDatabaseType(),
        driver.getDriverClassName(),
        metaData.getDatabaseProductName(),
        metaData.getDatabaseProductVersion(),
        trimToNull(connection.getCatalog()),
        trimToNull(connection.getSchema()),
        trimToNull(metaData.getIdentifierQuoteString()),
        parseProperties(dataSource.getConnectionProperties())
    );
    JdbcDatabaseDialect dialect = dialectManagementService.resolveDialect(supportContext)
        .orElseThrow(() -> new IllegalStateException("未找到可用的数据库方言"));
    return new RuntimeContext(connection, metaData, supportContext, dialect);
  }

  private static FederationJdbcDriverModels.TabularResult toTabularResult(
      final JdbcDatabaseDialect.MetadataResult result
  ) {
    if (result == null) {
      return new FederationJdbcDriverModels.TabularResult(List.of(), List.of());
    }
    return new FederationJdbcDriverModels.TabularResult(toColumns(result.columns()), result.rows());
  }

  private static List<FederationJdbcDriverModels.JdbcColumn> toColumns(
      final List<JdbcDatabaseDialect.MetadataColumn> columns
  ) {
    List<FederationJdbcDriverModels.JdbcColumn> values = new ArrayList<>();
    for (JdbcDatabaseDialect.MetadataColumn column : columns == null ? List.<JdbcDatabaseDialect.MetadataColumn>of() : columns) {
      if (column == null) {
        continue;
      }
      values.add(new FederationJdbcDriverModels.JdbcColumn(column.name(), column.typeName(), column.jdbcType()));
    }
    return List.copyOf(values);
  }

  private static List<String> extractStringValues(
      final JdbcDatabaseDialect.MetadataResult result,
      final String columnName
  ) {
    int columnIndex = findColumnIndex(result == null ? null : result.columns(), columnName);
    if (columnIndex < 0 || result == null) {
      return List.of();
    }
    return result.rows().stream()
        .map(row -> columnIndex < row.size() ? trimToNull(Objects.toString(row.get(columnIndex), null)) : null)
        .filter(Objects::nonNull)
        .distinct()
        .toList();
  }

  private static String resolveMetadataCatalog(final SchemaTarget target, final RuntimeContext context) {
    return trimToNull(target.catalog()) == null ? context.supportContext().currentCatalog() : target.catalog();
  }

  private static String resolveMetadataSchema(final SchemaTarget target, final RuntimeContext context) {
    return trimToNull(target.schema()) == null ? context.supportContext().currentSchema() : target.schema();
  }

  private static int findColumnIndex(
      final List<JdbcDatabaseDialect.MetadataColumn> columns,
      final String columnName
  ) {
    for (int index = 0; index < (columns == null ? List.<JdbcDatabaseDialect.MetadataColumn>of() : columns).size(); index++) {
      JdbcDatabaseDialect.MetadataColumn column = columns.get(index);
      if (column != null && columnName.equalsIgnoreCase(column.name())) {
        return index;
      }
    }
    return -1;
  }

  private static boolean matchesPattern(final String value, final String pattern) {
    String normalizedPattern = trimToNull(pattern);
    if (normalizedPattern == null) {
      return true;
    }
    String normalizedValue = trimToNull(value);
    if (normalizedValue == null) {
      return false;
    }
    if (!containsUnescapedWildcard(normalizedPattern)) {
      return normalizedValue.equalsIgnoreCase(stripEscapes(normalizedPattern));
    }
    StringBuilder builder = new StringBuilder("^");
    for (int index = 0; index < normalizedPattern.length(); index++) {
      char current = normalizedPattern.charAt(index);
      if (current == '\\' && index + 1 < normalizedPattern.length()) {
        char next = normalizedPattern.charAt(++index);
        if ("\\.[]{}()*+-?^$|".indexOf(next) >= 0) {
          builder.append('\\');
        }
        builder.append(next);
      } else if (current == '%') {
        builder.append(".*");
      } else if (current == '_') {
        builder.append('.');
      } else if ("\\.[]{}()*+-?^$|".indexOf(current) >= 0) {
        builder.append('\\').append(current);
      } else {
        builder.append(current);
      }
    }
    builder.append('$');
    return normalizedValue.toLowerCase(Locale.ROOT).matches(builder.toString().toLowerCase(Locale.ROOT));
  }

  private static boolean containsUnescapedWildcard(final String value) {
    if (value == null) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\\') {
        i++;
      } else if (c == '%' || c == '_') {
        return true;
      }
    }
    return false;
  }

  private static String stripEscapes(final String value) {
    if (value == null || value.indexOf('\\') < 0) {
      return value;
    }
    StringBuilder sb = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\\' && i + 1 < value.length()) {
        sb.append(value.charAt(++i));
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  private static String requireDataSourceCode(final JdbcDataSourceDefinition dataSource) {
    return requireValue(dataSource == null ? null : dataSource.getCode(), "数据源编码不能为空");
  }

  private static Map<String, String> parseProperties(final String text) {
    if (text == null || text.isBlank()) {
      return Map.of();
    }
    Properties properties = new Properties();
    try {
      properties.load(new StringReader(text));
    } catch (IOException ex) {
      throw new IllegalArgumentException("连接属性格式不正确: " + ex.getMessage(), ex);
    }
    Map<String, String> values = new LinkedHashMap<>();
    properties.forEach((key, value) -> values.put(String.valueOf(key), String.valueOf(value)));
    return Map.copyOf(values);
  }

  private static String rootMessage(final Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    String message = trimToNull(current.getMessage());
    return message == null ? current.getClass().getSimpleName() : message;
  }

  @FunctionalInterface
  private interface SqlCallback<T> {
    T execute(RuntimeContext context) throws SQLException;
  }

  private record RuntimeContext(
      Connection connection,
      DatabaseMetaData metaData,
      JdbcDatabaseDialect.SupportContext supportContext,
      JdbcDatabaseDialect dialect
  ) {
  }

  private record SchemaTarget(
      String schemaLabel,
      String catalog,
      String schema
  ) {
  }

  private static final class TabularResultAccumulator {

    private List<FederationJdbcDriverModels.JdbcColumn> columns = List.of();

    private final List<List<Object>> rows = new ArrayList<>();

    private void captureColumns(final List<FederationJdbcDriverModels.JdbcColumn> values) {
      if (!columns.isEmpty()) {
        return;
      }
      columns = values == null ? List.of() : List.copyOf(values);
    }

    private void addRow(
        final List<FederationJdbcDriverModels.JdbcColumn> columns,
        final List<Object> sourceRow,
        final Map<String, Object> overrides
    ) {
      Map<String, Object> normalizedOverrides = normalizeOverrides(overrides);
      List<Object> row = new ArrayList<>(columns == null ? 0 : columns.size());
      for (int index = 0; index < (columns == null ? List.<FederationJdbcDriverModels.JdbcColumn>of() : columns).size(); index++) {
        FederationJdbcDriverModels.JdbcColumn column = columns.get(index);
        String normalizedKey = column == null || column.name() == null ? "" : column.name().toUpperCase(Locale.ROOT);
        Object override = normalizedOverrides.get(normalizedKey);
        row.add(override == null && !normalizedOverrides.containsKey(normalizedKey)
            ? sourceRow != null && index < sourceRow.size() ? sourceRow.get(index) : null
            : override);
      }
      rows.add(row);
    }

    private void addAllRows(final List<List<Object>> newRows) {
      if (newRows != null) {
        rows.addAll(newRows);
      }
    }

    private FederationJdbcDriverModels.TabularResult build() {
      return new FederationJdbcDriverModels.TabularResult(columns, rows);
    }

    private static Map<String, Object> normalizeOverrides(final Map<String, Object> overrides) {
      Map<String, Object> normalized = new LinkedHashMap<>();
      if (overrides != null) {
        overrides.forEach((key, value) -> normalized.put(key == null ? "" : key.toUpperCase(Locale.ROOT), value));
      }
      return Map.copyOf(normalized);
    }
  }
}
