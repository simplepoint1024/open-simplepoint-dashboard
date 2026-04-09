package org.simplepoint.plugin.dna.federation.service.support;

import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.requireValue;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.trimToNull;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.ViewTable;
import org.simplepoint.data.calcite.core.query.CalciteSchemaConfigurer;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.core.api.service.JdbcDialectManagementService;
import org.simplepoint.plugin.dna.core.api.service.JdbcDriverDefinitionService;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.federation.api.entity.FederationCatalog;
import org.simplepoint.plugin.dna.federation.api.entity.FederationSchema;
import org.simplepoint.plugin.dna.federation.api.entity.FederationView;
import org.simplepoint.plugin.dna.federation.api.repository.FederationSchemaRepository;
import org.simplepoint.plugin.dna.federation.api.repository.FederationViewRepository;
import org.springframework.stereotype.Component;

/**
 * Assembles one federation catalog into a Calcite schema tree.
 */
@Component
public class FederationCalciteCatalogAssembler {

  private final JdbcDataSourceDefinitionService dataSourceService;

  private final JdbcDriverDefinitionService driverService;

  private final JdbcDialectManagementService dialectManagementService;

  private final FederationSchemaRepository schemaRepository;

  private final FederationViewRepository viewRepository;

  /**
   * Creates a Calcite catalog assembler.
   *
   * @param dataSourceService         datasource service
   * @param driverService             driver service
   * @param dialectManagementService  dialect service
   * @param schemaRepository          federation schema repository
   * @param viewRepository            federation view repository
   */
  public FederationCalciteCatalogAssembler(
      final JdbcDataSourceDefinitionService dataSourceService,
      final JdbcDriverDefinitionService driverService,
      final JdbcDialectManagementService dialectManagementService,
      final FederationSchemaRepository schemaRepository,
      final FederationViewRepository viewRepository
  ) {
    this.dataSourceService = dataSourceService;
    this.driverService = driverService;
    this.dialectManagementService = dialectManagementService;
    this.schemaRepository = schemaRepository;
    this.viewRepository = viewRepository;
  }

  /**
   * Builds a Calcite schema configuration for one federation catalog.
   *
   * @param catalog federation catalog
   * @return assembled Calcite catalog
   */
  public FederationCalciteCatalogAssembly assemble(final FederationCatalog catalog) {
    String catalogId = requireValue(catalog == null ? null : catalog.getId(), "联邦目录ID不能为空");
    String catalogCode = requireValue(catalog == null ? null : catalog.getCode(), "联邦目录编码不能为空");
    List<ResolvedJdbcSource> jdbcSources = dataSourceService.listEnabledDefinitions().stream()
        .sorted(Comparator.comparing(
            JdbcDataSourceDefinition::getCode,
            Comparator.nullsLast(String::compareTo)
        ))
        .map(this::resolveJdbcSource)
        .toList();
    if (jdbcSources.isEmpty()) {
      throw new IllegalStateException("当前没有可供联邦查询使用的已启用数据源");
    }
    List<FederationSchema> logicalSchemas = schemaRepository.findAllActiveByCatalogId(catalogId).stream()
        .filter(schema -> Boolean.TRUE.equals(schema.getEnabled()))
        .sorted(Comparator.comparing(FederationSchema::getCode, Comparator.nullsLast(String::compareTo)))
        .toList();
    List<String> schemaIds = logicalSchemas.stream()
        .map(FederationSchema::getId)
        .filter(value -> value != null && !value.isBlank())
        .toList();
    List<FederationView> logicalViews = schemaIds.isEmpty()
        ? List.of()
        : viewRepository.findAllActiveBySchemaIds(schemaIds).stream()
            .filter(view -> Boolean.TRUE.equals(view.getEnabled()))
            .sorted(Comparator.comparing(FederationView::getCode, Comparator.nullsLast(String::compareTo)))
            .toList();
    validateRegistrationNames(catalogCode, jdbcSources, logicalSchemas);
    return new FederationCalciteCatalogAssembly(
        catalogCode,
        jdbcSources.stream().map(source -> source.definition().getCode()).toList(),
        rootSchema -> configureRootSchema(rootSchema, catalogCode, jdbcSources, logicalSchemas, logicalViews),
        jdbcSources.stream()
            .flatMap(source -> source.cleanupDataSources().stream())
            .toList()
    );
  }

  private void configureRootSchema(
      final SchemaPlus rootSchema,
      final String catalogCode,
      final List<ResolvedJdbcSource> jdbcSources,
      final List<FederationSchema> logicalSchemas,
      final List<FederationView> logicalViews
  ) {
    SchemaPlus catalogSchema = rootSchema.add(catalogCode, new AbstractSchema());
    for (ResolvedJdbcSource jdbcSource : jdbcSources) {
      SchemaPlus dataSourceSchema = catalogSchema.add(
          jdbcSource.definition().getCode(),
          JdbcSchema.create(
              catalogSchema,
              jdbcSource.definition().getCode(),
              jdbcSource.simpleDataSource(),
              jdbcSource.metadataCatalog(),
              jdbcSource.metadataSchema()
          )
      );
      registerPhysicalNamespaces(dataSourceSchema, jdbcSource.physicalNamespaces());
      registerCatalogNamespaces(dataSourceSchema, jdbcSource.catalogNamespaces());
    }
    Map<String, SchemaPlus> logicalSchemaMap = new java.util.LinkedHashMap<>();
    for (FederationSchema logicalSchema : logicalSchemas) {
      logicalSchemaMap.put(logicalSchema.getId(), catalogSchema.add(logicalSchema.getCode(), new AbstractSchema()));
    }
    for (FederationView logicalView : logicalViews) {
      SchemaPlus viewSchema = logicalSchemaMap.get(logicalView.getSchemaId());
      if (viewSchema == null) {
        throw new IllegalStateException("逻辑视图关联的逻辑 Schema 不存在或已禁用: " + logicalView.getCode());
      }
      // View definitions resolve from the catalog root so they can reference physical datasource schemas directly.
      viewSchema.add(logicalView.getCode(), ViewTable.viewMacro(rootSchema, logicalView.getDefinitionSql(), List.of(catalogCode)));
    }
  }

  private ResolvedJdbcSource resolveJdbcSource(final JdbcDataSourceDefinition definition) {
    JdbcDriverDefinition driver = driverService.findActiveById(requireValue(
        definition.getDriverId(),
        "数据源驱动ID不能为空"
    )).orElseThrow(() -> new IllegalStateException("数据源关联驱动不存在: " + definition.getCode()));
    Map<String, String> connectionAttributes = parseProperties(definition.getConnectionProperties());
    SimpleDataSource simpleDataSource = dataSourceService.requireSimpleDataSource(requireValue(
        definition.getId(),
        "数据源ID不能为空"
    ));
    try (Connection connection = simpleDataSource.getConnection()) {
      DatabaseMetaData metaData = connection.getMetaData();
      JdbcDatabaseDialect.SupportContext supportContext = createSupportContext(
          driver,
          connection,
          metaData,
          connectionAttributes,
          null
      );
      JdbcDatabaseDialect dialect = dialectManagementService.resolveDialect(supportContext)
          .orElseThrow(() -> new IllegalStateException("未找到可用的数据库方言: " + definition.getCode()));
      List<ResolvedJdbcCatalogNamespace> catalogNamespaces = resolveCatalogNamespaces(
          definition,
          driver,
          simpleDataSource,
          metaData,
          dialect,
          supportContext,
          connectionAttributes
      );
      return new ResolvedJdbcSource(
          definition,
          simpleDataSource,
          dialect.metadataCatalog(supportContext.currentCatalog(), supportContext),
          dialect.metadataSchema(supportContext.currentSchema(), supportContext),
          resolvePhysicalNamespaces(simpleDataSource, metaData, dialect, supportContext.currentCatalog(), supportContext),
          catalogNamespaces,
          catalogNamespaces.stream()
              .filter(ResolvedJdbcCatalogNamespace::transientDataSource)
              .map(ResolvedJdbcCatalogNamespace::simpleDataSource)
              .toList()
      );
    } catch (SQLException ex) {
      throw new IllegalStateException("联邦数据源注册失败: " + definition.getCode() + " - " + ex.getMessage(), ex);
    }
  }

  private List<ResolvedJdbcCatalogNamespace> resolveCatalogNamespaces(
      final JdbcDataSourceDefinition definition,
      final JdbcDriverDefinition driver,
      final SimpleDataSource baseDataSource,
      final DatabaseMetaData baseMetaData,
      final JdbcDatabaseDialect dialect,
      final JdbcDatabaseDialect.SupportContext baseSupportContext,
      final Map<String, String> connectionAttributes
  ) throws SQLException {
    List<String> visibleCatalogs = dialect.visibleCatalogs(loadCatalogs(baseMetaData), baseSupportContext);
    Map<String, String> catalogNamesByLowerCase = new LinkedHashMap<>();
    registerCatalogName(catalogNamesByLowerCase, baseSupportContext.currentCatalog());
    for (String catalogName : visibleCatalogs) {
      registerCatalogName(catalogNamesByLowerCase, catalogName);
    }
    if (catalogNamesByLowerCase.isEmpty()) {
      return List.of();
    }
    String currentCatalog = trimToNull(baseSupportContext.currentCatalog());
    Map<String, ResolvedJdbcCatalogNamespace> namespacesByLowerCase = new LinkedHashMap<>();
    for (String normalizedCatalog : catalogNamesByLowerCase.values()) {
      String catalogKey = normalizedCatalog.toLowerCase(Locale.ROOT);
      if (namespacesByLowerCase.containsKey(catalogKey)) {
        continue;
      }
      if (dialect.requiresCatalogConnection(normalizedCatalog, baseSupportContext)) {
        String targetJdbcUrl = dialect.remapJdbcUrlCatalog(
            definition.getJdbcUrl(),
            normalizedCatalog,
            baseSupportContext
        );
        SimpleDataSource targetDataSource = dataSourceService.createTransientSimpleDataSource(
            definition.getId(),
            targetJdbcUrl
        );
        try (Connection targetConnection = targetDataSource.getConnection()) {
          DatabaseMetaData targetMetaData = targetConnection.getMetaData();
          JdbcDatabaseDialect.SupportContext targetSupportContext = createSupportContext(
              driver,
              targetConnection,
              targetMetaData,
              connectionAttributes,
              normalizedCatalog
          );
          namespacesByLowerCase.put(
              catalogKey,
              new ResolvedJdbcCatalogNamespace(
                  normalizedCatalog,
                  targetDataSource,
                  dialect.metadataCatalog(targetSupportContext.currentCatalog(), targetSupportContext),
                  null,
                  resolvePhysicalNamespaces(
                      targetDataSource,
                      targetMetaData,
                      dialect,
                      targetSupportContext.currentCatalog(),
                      targetSupportContext
                  ),
                  true
              )
          );
        } catch (RuntimeException | SQLException ex) {
          try {
            targetDataSource.close();
          } catch (RuntimeException closeEx) {
            ex.addSuppressed(closeEx);
          }
          throw ex;
        }
      } else {
        namespacesByLowerCase.put(
            catalogKey,
            new ResolvedJdbcCatalogNamespace(
                normalizedCatalog,
                baseDataSource,
                dialect.metadataCatalog(normalizedCatalog, baseSupportContext),
                null,
                resolvePhysicalNamespaces(baseDataSource, baseMetaData, dialect, normalizedCatalog, baseSupportContext),
                false
            )
        );
      }
    }
    return List.copyOf(namespacesByLowerCase.values());
  }

  private static JdbcDatabaseDialect.SupportContext createSupportContext(
      final JdbcDriverDefinition driver,
      final Connection connection,
      final DatabaseMetaData metaData,
      final Map<String, String> connectionAttributes,
      final String currentCatalogOverride
  ) throws SQLException {
    return new JdbcDatabaseDialect.SupportContext(
        driver.getDatabaseType(),
        driver.getDriverClassName(),
        metaData.getDatabaseProductName(),
        metaData.getDatabaseProductVersion(),
        trimToNull(currentCatalogOverride == null ? connection.getCatalog() : currentCatalogOverride),
        trimToNull(connection.getSchema()),
        trimToNull(metaData.getIdentifierQuoteString()),
        connectionAttributes == null ? Map.of() : Map.copyOf(connectionAttributes)
    );
  }

  private static void registerCatalogNamespaces(
      final SchemaPlus dataSourceSchema,
      final List<ResolvedJdbcCatalogNamespace> catalogNamespaces
  ) {
    if (catalogNamespaces == null || catalogNamespaces.isEmpty()) {
      return;
    }
    for (ResolvedJdbcCatalogNamespace catalogNamespace : catalogNamespaces) {
      SchemaPlus catalogSchema = dataSourceSchema.add(
          catalogNamespace.name(),
          JdbcSchema.create(
              dataSourceSchema,
              catalogNamespace.name(),
              catalogNamespace.simpleDataSource(),
              catalogNamespace.metadataCatalog(),
              catalogNamespace.metadataSchema()
          )
      );
      registerPhysicalNamespaces(catalogSchema, catalogNamespace.physicalNamespaces());
    }
  }

  private static void registerPhysicalNamespaces(
      final SchemaPlus dataSourceSchema,
      final List<ResolvedJdbcNamespace> physicalNamespaces
  ) {
    if (physicalNamespaces == null || physicalNamespaces.isEmpty()) {
      return;
    }
    for (ResolvedJdbcNamespace physicalNamespace : physicalNamespaces) {
      dataSourceSchema.add(
          physicalNamespace.name(),
          JdbcSchema.create(
              dataSourceSchema,
              physicalNamespace.name(),
              physicalNamespace.simpleDataSource(),
              physicalNamespace.metadataCatalog(),
              physicalNamespace.metadataSchema()
          )
      );
    }
  }

  private static List<ResolvedJdbcNamespace> resolvePhysicalNamespaces(
      final SimpleDataSource simpleDataSource,
      final DatabaseMetaData metaData,
      final JdbcDatabaseDialect dialect,
      final String catalog,
      final JdbcDatabaseDialect.SupportContext supportContext
  ) throws SQLException {
    Map<String, ResolvedJdbcNamespace> namespacesByLowerCase = new LinkedHashMap<>();
    String metadataCatalog = dialect.metadataCatalog(catalog, supportContext);
    registerNamespace(
        namespacesByLowerCase,
        trimToNull(supportContext.currentSchema()),
        simpleDataSource,
        metadataCatalog,
        dialect,
        supportContext
    );
    for (String schemaName : dialect.visibleSchemas(
        loadSchemas(metaData, dialect, catalog, supportContext),
        catalog,
        supportContext
    )) {
      registerNamespace(
          namespacesByLowerCase,
          schemaName,
          simpleDataSource,
          metadataCatalog,
          dialect,
          supportContext
      );
    }
    return List.copyOf(namespacesByLowerCase.values());
  }

  private static void registerNamespace(
      final Map<String, ResolvedJdbcNamespace> namespacesByLowerCase,
      final String schemaName,
      final SimpleDataSource simpleDataSource,
      final String metadataCatalog,
      final JdbcDatabaseDialect dialect,
      final JdbcDatabaseDialect.SupportContext supportContext
  ) {
    String registrationName = trimToNull(schemaName);
    if (registrationName == null) {
      return;
    }
    String namespaceKey = registrationName.toLowerCase(Locale.ROOT);
    if (namespacesByLowerCase.containsKey(namespaceKey)) {
      return;
    }
    String metadataSchema = trimToNull(dialect.metadataSchema(registrationName, supportContext));
    if (metadataSchema == null) {
      return;
    }
    namespacesByLowerCase.put(
        namespaceKey,
        new ResolvedJdbcNamespace(registrationName, simpleDataSource, metadataCatalog, metadataSchema)
    );
  }

  private static void registerCatalogName(
      final Map<String, String> catalogNamesByLowerCase,
      final String catalogName
  ) {
    String normalizedCatalog = trimToNull(catalogName);
    if (normalizedCatalog == null) {
      return;
    }
    catalogNamesByLowerCase.putIfAbsent(normalizedCatalog.toLowerCase(Locale.ROOT), normalizedCatalog);
  }

  private static List<String> loadSchemas(
      final DatabaseMetaData metaData,
      final JdbcDatabaseDialect dialect,
      final String catalog,
      final JdbcDatabaseDialect.SupportContext supportContext
  ) throws SQLException {
    String metadataCatalog = dialect.metadataCatalog(catalog, supportContext);
    Set<String> schemas = new LinkedHashSet<>();
    try (ResultSet resultSet = metaData.getSchemas(metadataCatalog, null)) {
      while (resultSet.next()) {
        String schema = trimToNull(resultSet.getString("TABLE_SCHEM"));
        if (schema != null) {
          schemas.add(schema);
        }
      }
    } catch (SQLException ex) {
      try (ResultSet resultSet = metaData.getSchemas()) {
        while (resultSet.next()) {
          String rowCatalog = trimToNull(resultSet.getString("TABLE_CATALOG"));
          String schema = trimToNull(resultSet.getString("TABLE_SCHEM"));
          if (schema == null) {
            continue;
          }
          if (metadataCatalog == null || metadataCatalog.equals(rowCatalog)) {
            schemas.add(schema);
          }
        }
      }
    }
    return List.copyOf(schemas);
  }

  private static List<String> loadCatalogs(final DatabaseMetaData metaData) throws SQLException {
    Set<String> catalogs = new LinkedHashSet<>();
    try (ResultSet resultSet = metaData.getCatalogs()) {
      while (resultSet.next()) {
        String catalog = trimToNull(resultSet.getString("TABLE_CAT"));
        if (catalog != null) {
          catalogs.add(catalog);
        }
      }
    } catch (SQLException ex) {
      return List.of();
    }
    return List.copyOf(catalogs);
  }

  private static void validateRegistrationNames(
      final String catalogCode,
      final List<ResolvedJdbcSource> jdbcSources,
      final List<FederationSchema> logicalSchemas
  ) {
    Set<String> registeredNames = new LinkedHashSet<>();
    for (ResolvedJdbcSource jdbcSource : jdbcSources) {
      String sourceCode = requireValue(jdbcSource.definition().getCode(), "数据源编码不能为空");
      if (!registeredNames.add(sourceCode)) {
        throw new IllegalStateException("联邦目录 " + catalogCode + " 下存在重复数据源编码: " + sourceCode);
      }
      validatePhysicalNamespaceNames(catalogCode, sourceCode, jdbcSource.physicalNamespaces(), jdbcSource.catalogNamespaces());
    }
    for (FederationSchema logicalSchema : logicalSchemas) {
      String schemaCode = requireValue(logicalSchema.getCode(), "逻辑 Schema 编码不能为空");
      if (!registeredNames.add(schemaCode)) {
        throw new IllegalStateException("联邦目录 " + catalogCode + " 下逻辑 Schema 编码与物理数据源编码冲突: " + schemaCode);
      }
    }
  }

  private static void validatePhysicalNamespaceNames(
      final String catalogCode,
      final String dataSourceCode,
      final List<ResolvedJdbcNamespace> physicalNamespaces,
      final List<ResolvedJdbcCatalogNamespace> catalogNamespaces
  ) {
    Set<String> rootNames = new LinkedHashSet<>();
    for (ResolvedJdbcNamespace physicalNamespace : physicalNamespaces) {
      String namespaceName = requireValue(physicalNamespace.name(), "物理 Schema 编码不能为空");
      if (!rootNames.add(namespaceName)) {
        throw new IllegalStateException("联邦目录 " + catalogCode + " 下数据源 " + dataSourceCode
            + " 存在重复物理命名空间: " + namespaceName);
      }
    }
    for (ResolvedJdbcCatalogNamespace catalogNamespace : catalogNamespaces) {
      String catalogName = requireValue(catalogNamespace.name(), "物理 Catalog 编码不能为空");
      if (!rootNames.add(catalogName)) {
        throw new IllegalStateException("联邦目录 " + catalogCode + " 下数据源 " + dataSourceCode
            + " 的 catalog 名称与 schema 名称冲突: " + catalogName);
      }
      Set<String> nestedNames = new LinkedHashSet<>();
      for (ResolvedJdbcNamespace physicalNamespace : catalogNamespace.physicalNamespaces()) {
        String namespaceName = requireValue(physicalNamespace.name(), "物理 Schema 编码不能为空");
        if (!nestedNames.add(namespaceName)) {
          throw new IllegalStateException("联邦目录 " + catalogCode + " 下数据源 " + dataSourceCode
              + " 的 catalog " + catalogName + " 存在重复 schema 名称: " + namespaceName);
        }
      }
    }
  }

  private static Map<String, String> parseProperties(final String rawProperties) {
    String normalized = trimToNull(rawProperties);
    if (normalized == null) {
      return Map.of();
    }
    Properties properties = new Properties();
    try (StringReader reader = new StringReader(normalized)) {
      properties.load(reader);
    } catch (Exception ex) {
      throw new IllegalArgumentException("连接属性格式不正确，请使用 Java Properties 格式", ex);
    }
    Map<String, String> values = new java.util.LinkedHashMap<>();
    properties.stringPropertyNames().forEach(name -> values.put(name, properties.getProperty(name)));
    return Map.copyOf(values);
  }

  /**
   * Immutable Calcite catalog assembly payload.
   *
   * @param catalogCode             federation catalog code
   * @param physicalDataSourceCodes physical datasource codes registered into the catalog
   * @param schemaConfigurer        Calcite schema registration callback
   */
  public record FederationCalciteCatalogAssembly(
      String catalogCode,
      List<String> physicalDataSourceCodes,
      CalciteSchemaConfigurer schemaConfigurer,
      List<SimpleDataSource> cleanupDataSources
  ) implements AutoCloseable {

    public FederationCalciteCatalogAssembly(
        final String catalogCode,
        final List<String> physicalDataSourceCodes,
        final CalciteSchemaConfigurer schemaConfigurer
    ) {
      this(catalogCode, physicalDataSourceCodes, schemaConfigurer, List.of());
    }

    /**
     * Creates an immutable assembly payload.
     *
     * @param catalogCode             federation catalog code
     * @param physicalDataSourceCodes physical datasource codes registered into the catalog
     * @param schemaConfigurer        Calcite schema registration callback
     */
    public FederationCalciteCatalogAssembly {
      catalogCode = requireValue(catalogCode, "联邦目录编码不能为空");
      physicalDataSourceCodes = physicalDataSourceCodes == null ? List.of() : List.copyOf(physicalDataSourceCodes);
      if (schemaConfigurer == null) {
        throw new IllegalArgumentException("schemaConfigurer 不能为空");
      }
      cleanupDataSources = cleanupDataSources == null ? List.of() : List.copyOf(cleanupDataSources);
    }

    @Override
    public void close() {
      RuntimeException failure = null;
      for (SimpleDataSource cleanupDataSource : cleanupDataSources) {
        if (cleanupDataSource == null) {
          continue;
        }
        try {
          cleanupDataSource.close();
        } catch (RuntimeException ex) {
          if (failure == null) {
            failure = ex;
          } else {
            failure.addSuppressed(ex);
          }
        }
      }
      if (failure != null) {
        throw failure;
      }
    }
  }

  private record ResolvedJdbcSource(
      JdbcDataSourceDefinition definition,
      SimpleDataSource simpleDataSource,
      String metadataCatalog,
      String metadataSchema,
      List<ResolvedJdbcNamespace> physicalNamespaces,
      List<ResolvedJdbcCatalogNamespace> catalogNamespaces,
      List<SimpleDataSource> cleanupDataSources
  ) {
  }

  private record ResolvedJdbcNamespace(
      String name,
      SimpleDataSource simpleDataSource,
      String metadataCatalog,
      String metadataSchema
  ) {
  }

  private record ResolvedJdbcCatalogNamespace(
      String name,
      SimpleDataSource simpleDataSource,
      String metadataCatalog,
      String metadataSchema,
      List<ResolvedJdbcNamespace> physicalNamespaces,
      boolean transientDataSource
  ) {
  }
}
