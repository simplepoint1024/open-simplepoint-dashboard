package org.simplepoint.plugin.dna.core.service.impl;

import java.io.IOException;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;
import org.simplepoint.plugin.dna.core.api.repository.JdbcDriverDefinitionRepository;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.core.api.service.JdbcDialectManagementService;
import org.simplepoint.plugin.dna.core.api.service.JdbcMetadataManagementService;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataRequests;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Runtime JDBC metadata browsing, preview, and DDL service implementation.
 */
@Service
public class JdbcMetadataManagementServiceImpl implements JdbcMetadataManagementService {

  private static final List<JdbcMetadataModels.Action> NAMESPACE_ACTIONS = List.of(
      JdbcMetadataModels.Action.REFRESH,
      JdbcMetadataModels.Action.CREATE_NAMESPACE,
      JdbcMetadataModels.Action.CREATE_TABLE,
      JdbcMetadataModels.Action.CREATE_VIEW,
      JdbcMetadataModels.Action.DROP_OBJECT
  );

  private static final List<JdbcMetadataModels.Action> VIEW_ACTIONS = List.of(
      JdbcMetadataModels.Action.REFRESH,
      JdbcMetadataModels.Action.PREVIEW_DATA,
      JdbcMetadataModels.Action.DROP_OBJECT
  );

  private static final List<JdbcMetadataModels.Action> COLUMN_ACTIONS = List.of(
      JdbcMetadataModels.Action.REFRESH,
      JdbcMetadataModels.Action.ALTER_COLUMN,
      JdbcMetadataModels.Action.DROP_COLUMN
  );

  private final JdbcDataSourceDefinitionService dataSourceService;

  private final JdbcDriverDefinitionRepository driverRepository;

  private final JdbcDialectManagementService dialectManagementService;

  /**
   * Creates the metadata management service implementation.
   *
   * @param dataSourceService datasource service
   * @param driverRepository driver repository
   * @param dialectManagementService dialect management service
   */
  public JdbcMetadataManagementServiceImpl(
      final JdbcDataSourceDefinitionService dataSourceService,
      final JdbcDriverDefinitionRepository driverRepository,
      final JdbcDialectManagementService dialectManagementService
  ) {
    this.dataSourceService = dataSourceService;
    this.driverRepository = driverRepository;
    this.dialectManagementService = dialectManagementService;
  }

  /** {@inheritDoc} */
  @Override
  public List<JdbcMetadataModels.TreeNode> children(
      final String dataSourceId,
      final JdbcMetadataRequests.PathRequest request
  ) {
    return withContext(dataSourceId, extractExplicitCatalog(request == null ? null : request.path()), context -> {
      ResolvedPath resolvedPath = resolvePath(normalizePath(request == null ? null : request.path()), context.supportContext());
      return switch (resolvedPath.leafType()) {
        case ROOT -> rootChildren(context);
        case DATABASE, CATALOG -> namespaceChildren(context, resolvedPath, true);
        case SCHEMA -> namespaceChildren(context, resolvedPath, false);
        case TABLE, VIEW -> columnChildren(context, resolvedPath);
        case COLUMN -> List.of();
      };
    });
  }

  /** {@inheritDoc} */
  @Override
  public JdbcMetadataModels.TableStructure structure(
      final String dataSourceId,
      final JdbcMetadataRequests.PathRequest request
  ) {
    return withContext(dataSourceId, extractExplicitCatalog(request == null ? null : request.path()), context -> {
      ResolvedPath tablePath = requireRelationPath(request == null ? null : request.path(), context.supportContext());
      return new JdbcMetadataModels.TableStructure(
          tablePath.path(),
          context.dialect().code(),
          context.dialect().name(),
          loadColumns(context, tablePath.catalog(), tablePath.schema(), tablePath.objectName()),
          context.dialect().loadConstraints(
              context.connection(),
              context.supportContext(),
              tablePath.catalog(),
              tablePath.schema(),
              tablePath.objectName()
          )
      );
    });
  }

  /** {@inheritDoc} */
  @Override
  public JdbcMetadataModels.DataPreviewPage preview(
      final String dataSourceId,
      final JdbcMetadataRequests.PathRequest request,
      final Pageable pageable
  ) {
    return withContext(dataSourceId, extractExplicitCatalog(request == null ? null : request.path()), context -> {
      if (!context.dialect().supportsDataPreview()) {
        throw new IllegalStateException("当前方言不支持数据预览");
      }
      ResolvedPath tablePath = requireRelationPath(request == null ? null : request.path(), context.supportContext());
      int pageSize = pageable == null || !pageable.isPaged() ? 20 : pageable.getPageSize();
      int pageNumber = pageable == null || !pageable.isPaged() ? 0 : pageable.getPageNumber();
      long offset = (long) pageNumber * pageSize;
      String qualifiedName = context.dialect().qualifyName(
          tablePath.catalog(),
          tablePath.schema(),
          tablePath.objectName(),
          context.supportContext()
      );
      String previewSql = context.dialect().buildPreviewSql(qualifiedName, offset, pageSize);
      String countSql = context.dialect().buildPreviewCountSql(qualifiedName);
      List<Map<String, Object>> content = new ArrayList<>();
      List<String> columns = List.of();
      try (Statement countStatement = context.connection().createStatement();
           ResultSet countResultSet = countStatement.executeQuery(countSql);
           Statement previewStatement = context.connection().createStatement();
           ResultSet resultSet = previewStatement.executeQuery(previewSql)) {
        ResultSetMetaData metaData = resultSet.getMetaData();
        List<String> columnNames = new ArrayList<>(metaData.getColumnCount());
        for (int index = 1; index <= metaData.getColumnCount(); index++) {
          columnNames.add(metaData.getColumnLabel(index));
        }
        while (resultSet.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int index = 1; index <= metaData.getColumnCount(); index++) {
            row.put(columnNames.get(index - 1), resultSet.getObject(index));
          }
          content.add(row);
        }
        columns = List.copyOf(columnNames);
        long total = countResultSet.next() ? countResultSet.getLong(1) : 0L;
        return new JdbcMetadataModels.DataPreviewPage(columns, List.copyOf(content), total, pageNumber, pageSize);
      }
    });
  }

  /** {@inheritDoc} */
  @Override
  public void createNamespace(
      final String dataSourceId,
      final JdbcMetadataRequests.NamespaceCreateRequest request
  ) {
    withTransaction(
        dataSourceId,
        request != null
            && (JdbcMetadataModels.NodeType.DATABASE.equals(request.type())
            || JdbcMetadataModels.NodeType.CATALOG.equals(request.type()))
            ? null
            : extractExplicitCatalog(request == null ? null : request.parentPath()),
        context -> {
          JdbcMetadataModels.NodeType type = request == null ? null : request.type();
          String name = requireName(request == null ? null : request.name(), "命名空间名称不能为空");
          if (type == null) {
            throw new IllegalArgumentException("命名空间类型不能为空");
          }
          validateNamespaceParent(request == null ? null : request.parentPath(), context.supportContext());
          execute(context.connection(), context.dialect().buildCreateNamespaceSql(type, name, context.supportContext()));
          return null;
        }
    );
  }

  /** {@inheritDoc} */
  @Override
  public void drop(final String dataSourceId, final JdbcMetadataRequests.DropRequest request) {
    withTransaction(dataSourceId, resolveTargetCatalogForDrop(request == null ? null : request.path()), context -> {
      ResolvedPath path = resolvePath(request == null ? null : request.path(), context.supportContext());
      boolean cascade = request != null && Boolean.TRUE.equals(request.cascade());
      String sql = switch (path.leafType()) {
        case TABLE -> context.dialect().buildDropTableSql(
            context.dialect().qualifyName(path.catalog(), path.schema(), path.objectName(), context.supportContext()),
            cascade
        );
        case VIEW -> context.dialect().buildDropViewSql(
            context.dialect().qualifyName(path.catalog(), path.schema(), path.objectName(), context.supportContext()),
            cascade
        );
        case DATABASE, CATALOG, SCHEMA -> context.dialect().buildDropNamespaceSql(
            path.leafType(),
            path.objectName(),
            cascade,
            context.supportContext()
        );
        case ROOT, COLUMN -> throw new IllegalArgumentException("当前路径不支持删除操作");
      };
      execute(context.connection(), sql);
      return null;
    });
  }

  /** {@inheritDoc} */
  @Override
  public void createTable(
      final String dataSourceId,
      final JdbcMetadataRequests.TableCreateRequest request
  ) {
    withTransaction(dataSourceId, extractExplicitCatalog(request == null ? null : request.parentPath()), context -> {
      NamespacePath namespacePath = resolveNamespace(request == null ? null : request.parentPath(), context.supportContext());
      String tableName = requireName(request == null ? null : request.name(), "数据表名称不能为空");
      String qualifiedName = context.dialect().qualifyName(
          namespacePath.catalog(),
          namespacePath.schema(),
          tableName,
          context.supportContext()
      );
      execute(
          context.connection(),
          context.dialect().buildCreateTableSql(
              qualifiedName,
              request.columns(),
              safeConstraints(request.constraints()),
              context.supportContext()
          )
      );
      return null;
    });
  }

  /** {@inheritDoc} */
  @Override
  public void createView(
      final String dataSourceId,
      final JdbcMetadataRequests.ViewCreateRequest request
  ) {
    withTransaction(dataSourceId, extractExplicitCatalog(request == null ? null : request.parentPath()), context -> {
      NamespacePath namespacePath = resolveNamespace(request == null ? null : request.parentPath(), context.supportContext());
      String viewName = requireName(request == null ? null : request.name(), "视图名称不能为空");
      String definitionSql = requireName(request == null ? null : request.definitionSql(), "视图SQL不能为空");
      execute(
          context.connection(),
          context.dialect().buildCreateViewSql(
              context.dialect().qualifyName(
                  namespacePath.catalog(),
                  namespacePath.schema(),
                  viewName,
                  context.supportContext()
              ),
              definitionSql
          )
      );
      return null;
    });
  }

  /** {@inheritDoc} */
  @Override
  public void addColumn(
      final String dataSourceId,
      final JdbcMetadataRequests.ColumnAddRequest request
  ) {
    withTransaction(dataSourceId, extractExplicitCatalog(request == null ? null : request.tablePath()), context -> {
      ResolvedPath tablePath = requireTablePath(request == null ? null : request.tablePath(), context.supportContext());
      execute(
          context.connection(),
          context.dialect().buildAddColumnSql(
              context.dialect().qualifyName(
                  tablePath.catalog(),
                  tablePath.schema(),
                  tablePath.objectName(),
                  context.supportContext()
              ),
              requireColumn(request == null ? null : request.column()),
              context.supportContext()
          )
      );
      return null;
    });
  }

  /** {@inheritDoc} */
  @Override
  public void alterColumn(
      final String dataSourceId,
      final JdbcMetadataRequests.ColumnAlterRequest request
  ) {
    withTransaction(dataSourceId, extractExplicitCatalog(request == null ? null : request.tablePath()), context -> {
      ResolvedPath tablePath = requireTablePath(request == null ? null : request.tablePath(), context.supportContext());
      List<String> sql = context.dialect().buildAlterColumnSql(
          context.dialect().qualifyName(
              tablePath.catalog(),
              tablePath.schema(),
              tablePath.objectName(),
              context.supportContext()
          ),
          requireName(request == null ? null : request.currentName(), "当前字段名称不能为空"),
          requireColumn(request == null ? null : request.column()),
          context.supportContext()
      );
      sql.forEach(statement -> execute(context.connection(), statement));
      return null;
    });
  }

  /** {@inheritDoc} */
  @Override
  public void dropColumn(
      final String dataSourceId,
      final JdbcMetadataRequests.ColumnDropRequest request
  ) {
    withTransaction(dataSourceId, extractExplicitCatalog(request == null ? null : request.tablePath()), context -> {
      ResolvedPath tablePath = requireTablePath(request == null ? null : request.tablePath(), context.supportContext());
      execute(
          context.connection(),
          context.dialect().buildDropColumnSql(
              context.dialect().qualifyName(
                  tablePath.catalog(),
                  tablePath.schema(),
                  tablePath.objectName(),
                  context.supportContext()
              ),
              requireName(request == null ? null : request.columnName(), "字段名称不能为空"),
              context.supportContext()
          )
      );
      return null;
    });
  }

  /** {@inheritDoc} */
  @Override
  public void addConstraint(
      final String dataSourceId,
      final JdbcMetadataRequests.ConstraintAddRequest request
  ) {
    withTransaction(dataSourceId, extractExplicitCatalog(request == null ? null : request.tablePath()), context -> {
      ResolvedPath tablePath = requireTablePath(request == null ? null : request.tablePath(), context.supportContext());
      execute(
          context.connection(),
          context.dialect().buildAddConstraintSql(
              context.dialect().qualifyName(
                  tablePath.catalog(),
                  tablePath.schema(),
                  tablePath.objectName(),
                  context.supportContext()
              ),
              requireConstraint(request == null ? null : request.constraint()),
              context.supportContext()
          )
      );
      return null;
    });
  }

  /** {@inheritDoc} */
  @Override
  public void dropConstraint(
      final String dataSourceId,
      final JdbcMetadataRequests.ConstraintDropRequest request
  ) {
    withTransaction(dataSourceId, extractExplicitCatalog(request == null ? null : request.tablePath()), context -> {
      ResolvedPath tablePath = requireTablePath(request == null ? null : request.tablePath(), context.supportContext());
      JdbcMetadataModels.ConstraintType type = request == null ? null : request.type();
      if (type == null) {
        throw new IllegalArgumentException("约束类型不能为空");
      }
      execute(
          context.connection(),
          context.dialect().buildDropConstraintSql(
              context.dialect().qualifyName(
                  tablePath.catalog(),
                  tablePath.schema(),
                  tablePath.objectName(),
                  context.supportContext()
              ),
              requireName(request == null ? null : request.constraintName(), "约束名称不能为空"),
              type,
              context.supportContext()
          )
      );
      return null;
    });
  }

  private List<JdbcMetadataModels.TreeNode> rootChildren(final RuntimeContext context) throws SQLException {
    List<String> catalogs = context.dialect().visibleCatalogs(loadCatalogs(context.metaData()), context.supportContext());
    if (!catalogs.isEmpty()) {
      return catalogs.stream()
          .map(name -> namespaceNode(context, List.of(), context.dialect().catalogNodeType(), name))
          .sorted(treeNodeComparator())
          .toList();
    }
    List<String> schemas = context.dialect().visibleSchemas(
        loadSchemas(context, context.supportContext().currentCatalog()),
        context.supportContext().currentCatalog(),
        context.supportContext()
    );
    if (!schemas.isEmpty()) {
      return schemas.stream()
          .map(name -> namespaceNode(context, List.of(), JdbcMetadataModels.NodeType.SCHEMA, name))
          .sorted(treeNodeComparator())
          .toList();
    }
    return relationNodes(
        context,
        List.of(),
        context.supportContext().currentCatalog(),
        context.supportContext().currentSchema()
    );
  }

  private List<JdbcMetadataModels.TreeNode> namespaceChildren(
      final RuntimeContext context,
      final ResolvedPath parentPath,
      final boolean allowSchemaChildren
  ) throws SQLException {
    String catalog = firstNonBlank(parentPath.catalog(), context.supportContext().currentCatalog());
    if (allowSchemaChildren) {
      List<String> schemas = context.dialect().visibleSchemas(loadSchemas(context, catalog), catalog, context.supportContext());
      if (!schemas.isEmpty()) {
        return schemas.stream()
            .map(name -> namespaceNode(context, parentPath.path(), JdbcMetadataModels.NodeType.SCHEMA, name))
            .sorted(treeNodeComparator())
            .toList();
      }
    }
    return relationNodes(
        context,
        parentPath.path(),
        catalog,
        firstNonBlank(parentPath.schema(), context.supportContext().currentSchema())
    );
  }

  private List<JdbcMetadataModels.TreeNode> columnChildren(
      final RuntimeContext context,
      final ResolvedPath tablePath
  ) throws SQLException {
    return loadColumns(context, tablePath.catalog(), tablePath.schema(), tablePath.objectName()).stream()
        .map(column -> new JdbcMetadataModels.TreeNode(
            key(append(tablePath.path(), new JdbcMetadataModels.PathSegment(JdbcMetadataModels.NodeType.COLUMN, column.name()))),
            column.name(),
            JdbcMetadataModels.NodeType.COLUMN,
            typeLabel(JdbcMetadataModels.NodeType.COLUMN),
            append(tablePath.path(), new JdbcMetadataModels.PathSegment(JdbcMetadataModels.NodeType.COLUMN, column.name())),
            true,
            COLUMN_ACTIONS,
            displayType(column),
            column.nullable(),
            column.defaultValue(),
            column.remarks()
        ))
        .toList();
  }

  private List<JdbcMetadataModels.TreeNode> relationNodes(
      final RuntimeContext context,
      final List<JdbcMetadataModels.PathSegment> parentPath,
      final String catalog,
      final String schema
  ) throws SQLException {
    List<JdbcMetadataModels.TreeNode> nodes = new ArrayList<>();
    String metadataCatalog = context.dialect().metadataCatalog(catalog, context.supportContext());
    String metadataSchema = context.dialect().metadataSchema(schema, context.supportContext());
    try (ResultSet resultSet = context.metaData().getTables(
        metadataCatalog,
        metadataSchema,
        "%",
        new String[]{"TABLE", "VIEW"}
    )) {
      while (resultSet.next()) {
        String tableType = resultSet.getString("TABLE_TYPE");
        JdbcMetadataModels.NodeType type = "VIEW".equalsIgnoreCase(tableType)
            ? JdbcMetadataModels.NodeType.VIEW
            : JdbcMetadataModels.NodeType.TABLE;
        List<JdbcMetadataModels.PathSegment> path = append(parentPath, new JdbcMetadataModels.PathSegment(type, resultSet.getString("TABLE_NAME")));
        nodes.add(new JdbcMetadataModels.TreeNode(
            key(path),
            resultSet.getString("TABLE_NAME"),
            type,
            typeLabel(type),
            path,
            false,
            JdbcMetadataModels.NodeType.VIEW.equals(type) ? VIEW_ACTIONS : tableActions(context),
            null,
            null,
            null,
            resultSet.getString("REMARKS")
        ));
      }
    }
    return nodes.stream().sorted(treeNodeComparator()).toList();
  }

  private List<JdbcMetadataModels.Action> tableActions(final RuntimeContext context) {
    List<JdbcMetadataModels.Action> actions = new ArrayList<>();
    actions.add(JdbcMetadataModels.Action.REFRESH);
    actions.add(JdbcMetadataModels.Action.PREVIEW_DATA);
    actions.add(JdbcMetadataModels.Action.ADD_COLUMN);
    actions.add(JdbcMetadataModels.Action.ALTER_COLUMN);
    actions.add(JdbcMetadataModels.Action.DROP_COLUMN);
    if (context.dialect().supportsConstraintManagement()) {
      actions.add(JdbcMetadataModels.Action.ADD_CONSTRAINT);
      actions.add(JdbcMetadataModels.Action.DROP_CONSTRAINT);
    }
    actions.add(JdbcMetadataModels.Action.DROP_OBJECT);
    return List.copyOf(actions);
  }

  private JdbcMetadataModels.TreeNode namespaceNode(
      final RuntimeContext context,
      final List<JdbcMetadataModels.PathSegment> parentPath,
      final JdbcMetadataModels.NodeType type,
      final String name
  ) {
    final List<JdbcMetadataModels.PathSegment> path = append(parentPath, new JdbcMetadataModels.PathSegment(type, name));
    List<JdbcMetadataModels.Action> actions = new ArrayList<>();
    actions.add(JdbcMetadataModels.Action.REFRESH);
    if (canCreateNamespaceBelow(context.dialect(), type)) {
      actions.add(JdbcMetadataModels.Action.CREATE_NAMESPACE);
    }
    actions.add(JdbcMetadataModels.Action.CREATE_TABLE);
    actions.add(JdbcMetadataModels.Action.CREATE_VIEW);
    if (context.dialect().supportsNamespaceDrop(type)) {
      actions.add(JdbcMetadataModels.Action.DROP_OBJECT);
    }
    return new JdbcMetadataModels.TreeNode(
        key(path),
        name,
        type,
        typeLabel(type),
        path,
        false,
        List.copyOf(actions),
        null,
        null,
        null,
        null
    );
  }

  private List<JdbcMetadataModels.ColumnDefinition> loadColumns(
      final RuntimeContext context,
      final String catalog,
      final String schema,
      final String tableName
  ) throws SQLException {
    List<JdbcMetadataModels.ColumnDefinition> columns = new ArrayList<>();
    String metadataCatalog = context.dialect().metadataCatalog(catalog, context.supportContext());
    String metadataSchema = context.dialect().metadataSchema(schema, context.supportContext());
    try (ResultSet resultSet = context.metaData().getColumns(metadataCatalog, metadataSchema, tableName, "%")) {
      while (resultSet.next()) {
        columns.add(new JdbcMetadataModels.ColumnDefinition(
            resultSet.getString("COLUMN_NAME"),
            resultSet.getString("TYPE_NAME"),
            getNullableInteger(resultSet, "COLUMN_SIZE"),
            getNullableInteger(resultSet, "DECIMAL_DIGITS"),
            toNullableBoolean(resultSet.getInt("NULLABLE"), resultSet.wasNull()),
            resultSet.getString("COLUMN_DEF"),
            parseYesNo(resultSet.getString("IS_AUTOINCREMENT")),
            resultSet.getString("REMARKS")
        ));
      }
    }
    return columns;
  }

  private List<String> loadCatalogs(final DatabaseMetaData metaData) throws SQLException {
    Set<String> catalogs = new LinkedHashSet<>();
    try (ResultSet resultSet = metaData.getCatalogs()) {
      while (resultSet.next()) {
        String catalog = resultSet.getString("TABLE_CAT");
        if (catalog != null && !catalog.isBlank()) {
          catalogs.add(catalog);
        }
      }
    } catch (SQLException ex) {
      return List.of();
    }
    return List.copyOf(catalogs);
  }

  private List<String> loadSchemas(final RuntimeContext context, final String catalog) throws SQLException {
    DatabaseMetaData metaData = context.metaData();
    String metadataCatalog = context.dialect().metadataCatalog(catalog, context.supportContext());
    Set<String> schemas = new LinkedHashSet<>();
    try (ResultSet resultSet = metaData.getSchemas(metadataCatalog, null)) {
      while (resultSet.next()) {
        String schema = resultSet.getString("TABLE_SCHEM");
        if (schema != null && !schema.isBlank()) {
          schemas.add(schema);
        }
      }
    } catch (SQLException ex) {
      try (ResultSet resultSet = metaData.getSchemas()) {
        while (resultSet.next()) {
          String rowCatalog = resultSet.getString("TABLE_CATALOG");
          String schema = resultSet.getString("TABLE_SCHEM");
          if (schema == null || schema.isBlank()) {
            continue;
          }
          if (metadataCatalog == null || metadataCatalog.isBlank() || metadataCatalog.equals(rowCatalog)) {
            schemas.add(schema);
          }
        }
      }
    }
    return List.copyOf(schemas);
  }

  private <T> T withContext(
      final String dataSourceId,
      final String targetCatalog,
      final SqlCallback<T> callback
  ) {
    JdbcDataSourceDefinition dataSource = dataSourceService.findActiveById(dataSourceId)
        .orElseThrow(() -> new IllegalArgumentException("数据源不存在: " + dataSourceId));
    JdbcDriverDefinition driver = driverRepository.findActiveById(dataSource.getDriverId())
        .orElseThrow(() -> new IllegalArgumentException("驱动不存在: " + dataSource.getDriverId()));
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
        try (Connection targetConnection = dataSourceService.createTransientSimpleDataSource(dataSourceId, targetJdbcUrl)
            .getConnection()) {
          return callback.execute(createRuntimeContext(targetConnection, driver, dataSource));
        }
      }
      return callback.execute(runtimeContext);
    } catch (SQLException ex) {
      throw new IllegalStateException("访问数据库元数据失败: " + ex.getMessage(), ex);
    }
  }

  private <T> T withTransaction(
      final String dataSourceId,
      final String targetCatalog,
      final SqlCallback<T> callback
  ) {
    return withContext(dataSourceId, targetCatalog, context -> {
      boolean previousAutoCommit = context.connection().getAutoCommit();
      try {
        context.connection().setAutoCommit(false);
        T result = callback.execute(context);
        context.connection().commit();
        return result;
      } catch (Exception ex) {
        try {
          context.connection().rollback();
        } catch (SQLException rollbackEx) {
          ex.addSuppressed(rollbackEx);
        }
        if (ex instanceof RuntimeException runtimeException) {
          throw runtimeException;
        }
        throw new IllegalStateException("执行元数据管理操作失败: " + ex.getMessage(), ex);
      } finally {
        context.connection().setAutoCommit(previousAutoCommit);
      }
    });
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

  private static String extractExplicitCatalog(final List<JdbcMetadataModels.PathSegment> path) {
    return normalizePath(path).stream()
        .filter(segment -> JdbcMetadataModels.NodeType.DATABASE.equals(segment.type())
            || JdbcMetadataModels.NodeType.CATALOG.equals(segment.type()))
        .map(JdbcMetadataModels.PathSegment::name)
        .map(JdbcMetadataManagementServiceImpl::trimToNull)
        .filter(value -> value != null && !value.isBlank())
        .findFirst()
        .orElse(null);
  }

  private static String resolveTargetCatalogForDrop(final List<JdbcMetadataModels.PathSegment> path) {
    List<JdbcMetadataModels.PathSegment> normalizedPath = normalizePath(path);
    if (normalizedPath.isEmpty()) {
      return null;
    }
    JdbcMetadataModels.NodeType leafType = normalizedPath.get(normalizedPath.size() - 1).type();
    if (JdbcMetadataModels.NodeType.DATABASE.equals(leafType)
        || JdbcMetadataModels.NodeType.CATALOG.equals(leafType)) {
      return null;
    }
    return extractExplicitCatalog(normalizedPath);
  }

  private static void execute(final Connection connection, final String sql) {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException ex) {
      throw new IllegalStateException("执行 SQL 失败: " + ex.getMessage(), ex);
    }
  }

  private static ResolvedPath requireRelationPath(
      final List<JdbcMetadataModels.PathSegment> path,
      final JdbcDatabaseDialect.SupportContext context
  ) {
    ResolvedPath resolved = resolvePath(path, context);
    if (!JdbcMetadataModels.NodeType.TABLE.equals(resolved.leafType())
        && !JdbcMetadataModels.NodeType.VIEW.equals(resolved.leafType())) {
      throw new IllegalArgumentException("当前路径必须指向数据表或视图");
    }
    return resolved;
  }

  private static ResolvedPath requireTablePath(
      final List<JdbcMetadataModels.PathSegment> path,
      final JdbcDatabaseDialect.SupportContext context
  ) {
    ResolvedPath resolved = resolvePath(path, context);
    if (!JdbcMetadataModels.NodeType.TABLE.equals(resolved.leafType())) {
      throw new IllegalArgumentException("当前路径必须指向数据表");
    }
    return resolved;
  }

  private static NamespacePath resolveNamespace(
      final List<JdbcMetadataModels.PathSegment> path,
      final JdbcDatabaseDialect.SupportContext context
  ) {
    ResolvedPath resolved = resolvePath(path, context);
    return switch (resolved.leafType()) {
      case ROOT -> new NamespacePath(context.currentCatalog(), context.currentSchema());
      case DATABASE, CATALOG -> new NamespacePath(resolved.objectName(), context.currentSchema());
      case SCHEMA -> new NamespacePath(firstNonBlank(resolved.catalog(), context.currentCatalog()), resolved.objectName());
      default -> throw new IllegalArgumentException("当前路径必须指向数据库、catalog、schema 或根节点");
    };
  }

  private static void validateNamespaceParent(
      final List<JdbcMetadataModels.PathSegment> path,
      final JdbcDatabaseDialect.SupportContext context
  ) {
    ResolvedPath resolved = resolvePath(path, context);
    if (JdbcMetadataModels.NodeType.TABLE.equals(resolved.leafType())
        || JdbcMetadataModels.NodeType.VIEW.equals(resolved.leafType())
        || JdbcMetadataModels.NodeType.COLUMN.equals(resolved.leafType())) {
      throw new IllegalArgumentException("命名空间只能创建在根节点或命名空间节点下");
    }
  }

  private static ResolvedPath resolvePath(
      final List<JdbcMetadataModels.PathSegment> rawPath,
      final JdbcDatabaseDialect.SupportContext context
  ) {
    List<JdbcMetadataModels.PathSegment> path = normalizePath(rawPath);
    if (path.isEmpty()) {
      return new ResolvedPath(List.of(), null, null, null, null, JdbcMetadataModels.NodeType.ROOT);
    }
    String catalog = null;
    String schema = null;
    String objectName = null;
    String columnName = null;
    JdbcMetadataModels.NodeType leafType = JdbcMetadataModels.NodeType.ROOT;
    for (JdbcMetadataModels.PathSegment segment : path) {
      String name = requireName(segment.name(), "元数据路径名称不能为空");
      leafType = segment.type();
      switch (segment.type()) {
        case DATABASE, CATALOG -> {
          catalog = name;
          objectName = name;
        }
        case SCHEMA -> {
          schema = name;
          objectName = name;
        }
        case TABLE, VIEW -> objectName = name;
        case COLUMN -> columnName = name;
        case ROOT -> {
        }
        default -> {
        }
      }
    }
    if ((JdbcMetadataModels.NodeType.TABLE.equals(leafType) || JdbcMetadataModels.NodeType.VIEW.equals(leafType))
        && catalog == null) {
      catalog = context.currentCatalog();
    }
    if ((JdbcMetadataModels.NodeType.TABLE.equals(leafType) || JdbcMetadataModels.NodeType.VIEW.equals(leafType))
        && schema == null) {
      schema = context.currentSchema();
    }
    return new ResolvedPath(path, trimToNull(catalog), trimToNull(schema), objectName, columnName, leafType);
  }

  private static JdbcMetadataModels.ColumnDefinition requireColumn(final JdbcMetadataModels.ColumnDefinition column) {
    if (column == null) {
      throw new IllegalArgumentException("字段定义不能为空");
    }
    requireName(column.name(), "字段名称不能为空");
    requireName(column.typeName(), "字段类型不能为空");
    return column;
  }

  private static JdbcMetadataModels.ConstraintDefinition requireConstraint(
      final JdbcMetadataModels.ConstraintDefinition constraint
  ) {
    if (constraint == null) {
      throw new IllegalArgumentException("约束定义不能为空");
    }
    if (constraint.type() == null) {
      throw new IllegalArgumentException("约束类型不能为空");
    }
    return constraint;
  }

  private static List<JdbcMetadataModels.ConstraintDefinition> safeConstraints(
      final List<JdbcMetadataModels.ConstraintDefinition> constraints
  ) {
    return constraints == null ? List.of() : constraints;
  }

  private static List<JdbcMetadataModels.PathSegment> normalizePath(
      final List<JdbcMetadataModels.PathSegment> path
  ) {
    if (path == null || path.isEmpty()) {
      return List.of();
    }
    return path.stream()
        .filter(segment -> segment != null && segment.type() != null)
        .toList();
  }

  private static String typeLabel(final JdbcMetadataModels.NodeType type) {
    return switch (type) {
      case ROOT -> "根节点";
      case DATABASE -> "数据库";
      case CATALOG -> "Catalog";
      case SCHEMA -> "Schema";
      case TABLE -> "数据表";
      case VIEW -> "视图";
      case COLUMN -> "字段";
    };
  }

  private static Comparator<JdbcMetadataModels.TreeNode> treeNodeComparator() {
    return Comparator.comparing((JdbcMetadataModels.TreeNode node) -> node.type().ordinal())
        .thenComparing(JdbcMetadataModels.TreeNode::title, String.CASE_INSENSITIVE_ORDER);
  }

  private static List<JdbcMetadataModels.PathSegment> append(
      final List<JdbcMetadataModels.PathSegment> path,
      final JdbcMetadataModels.PathSegment segment
  ) {
    List<JdbcMetadataModels.PathSegment> copy = new ArrayList<>(path == null ? List.of() : path);
    copy.add(segment);
    return List.copyOf(copy);
  }

  private static String key(final Collection<JdbcMetadataModels.PathSegment> path) {
    return path.stream()
        .map(segment -> segment.type().name() + ":" + segment.name())
        .reduce((left, right) -> left + "/" + right)
        .orElse(JdbcMetadataModels.NodeType.ROOT.name());
  }

  private static boolean canCreateNamespaceBelow(
      final JdbcDatabaseDialect dialect,
      final JdbcMetadataModels.NodeType currentType
  ) {
    if (JdbcMetadataModels.NodeType.DATABASE.equals(currentType) || JdbcMetadataModels.NodeType.CATALOG.equals(currentType)) {
      return dialect.supportsNamespaceCreate(JdbcMetadataModels.NodeType.SCHEMA);
    }
    return false;
  }

  private static String displayType(final JdbcMetadataModels.ColumnDefinition column) {
    if (column == null) {
      return null;
    }
    if (column.size() == null || column.size() <= 0) {
      return column.typeName();
    }
    if (column.scale() == null || column.scale() < 0) {
      return column.typeName() + "(" + column.size() + ")";
    }
    return column.typeName() + "(" + column.size() + "," + column.scale() + ")";
  }

  private static Integer getNullableInteger(final ResultSet resultSet, final String columnLabel) throws SQLException {
    int value = resultSet.getInt(columnLabel);
    return resultSet.wasNull() ? null : value;
  }

  private static Boolean parseYesNo(final String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return "YES".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value);
  }

  private static Boolean toNullableBoolean(final int nullable, final boolean wasNull) {
    if (wasNull) {
      return null;
    }
    return nullable == DatabaseMetaData.columnNullable;
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

  private static String requireName(final String value, final String message) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      throw new IllegalArgumentException(message);
    }
    return normalized;
  }

  private static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String firstNonBlank(final String first, final String second) {
    return trimToNull(first) != null ? trimToNull(first) : trimToNull(second);
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

  private record ResolvedPath(
      List<JdbcMetadataModels.PathSegment> path,
      String catalog,
      String schema,
      String objectName,
      String columnName,
      JdbcMetadataModels.NodeType leafType
  ) {
  }

  private record NamespacePath(
      String catalog,
      String schema
  ) {
  }
}
