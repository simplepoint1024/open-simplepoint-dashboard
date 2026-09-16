package org.simplepoint.plugin.dna.core.service.impl;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.dna.core.api.repository.JdbcDriverDefinitionRepository;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.core.api.service.JdbcDialectManagementService;
import org.simplepoint.plugin.dna.core.api.service.JdbcMetadataBrowserService;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataRequests;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Runtime JDBC metadata browsing and data preview service implementation.
 */
@Service
public class JdbcMetadataBrowserServiceImpl extends AbstractJdbcMetadataService
    implements JdbcMetadataBrowserService {

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

  /**
   * Creates the browser service implementation.
   *
   * @param dataSourceService datasource service
   * @param driverRepository driver repository
   * @param dialectManagementService dialect management service
   */
  public JdbcMetadataBrowserServiceImpl(
      final JdbcDataSourceDefinitionService dataSourceService,
      final JdbcDriverDefinitionRepository driverRepository,
      final JdbcDialectManagementService dialectManagementService
  ) {
    super(dataSourceService, driverRepository, dialectManagementService);
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

  private List<JdbcMetadataModels.TreeNode> rootChildren(final RuntimeContext context) throws SQLException {
    List<String> catalogs = extractStringValues(
        context.dialect().loadCatalogs(
            context.connection(),
            context.metaData(),
            context.supportContext(),
            null
        ),
        "TABLE_CAT"
    );
    if (!catalogs.isEmpty()) {
      return catalogs.stream()
          .map(name -> namespaceNode(context, List.of(), context.dialect().catalogNodeType(), name))
          .sorted(treeNodeComparator())
          .toList();
    }
    List<String> schemas = extractStringValues(
        context.dialect().loadSchemas(
            context.connection(),
            context.metaData(),
            context.supportContext(),
            context.supportContext().currentCatalog(),
            null
        ),
        "TABLE_SCHEM"
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
      List<String> schemas = extractStringValues(
          context.dialect().loadSchemas(
              context.connection(),
              context.metaData(),
              context.supportContext(),
              catalog,
              null
          ),
          "TABLE_SCHEM"
      );
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
    JdbcDatabaseDialect.MetadataResult metadata = context.dialect().loadTables(
        context.connection(),
        context.metaData(),
        context.supportContext(),
        catalog,
        exactMetadataPattern(context, schema),
        "%",
        List.of("TABLE", "VIEW")
    );
    for (List<Object> row : metadata.rows()) {
      String tableName = stringValue(metadata, row, "TABLE_NAME");
      if (tableName == null) {
        continue;
      }
      String tableType = stringValue(metadata, row, "TABLE_TYPE");
      JdbcMetadataModels.NodeType type = "VIEW".equalsIgnoreCase(tableType)
          ? JdbcMetadataModels.NodeType.VIEW
          : JdbcMetadataModels.NodeType.TABLE;
      List<JdbcMetadataModels.PathSegment> path = append(parentPath, new JdbcMetadataModels.PathSegment(type, tableName));
      nodes.add(new JdbcMetadataModels.TreeNode(
          key(path),
          tableName,
          type,
          typeLabel(type),
          path,
          false,
          JdbcMetadataModels.NodeType.VIEW.equals(type) ? VIEW_ACTIONS : tableActions(context),
          null,
          null,
          null,
          stringValue(metadata, row, "REMARKS")
      ));
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
    JdbcDatabaseDialect.MetadataResult metadata = context.dialect().loadColumns(
        context.connection(),
        context.metaData(),
        context.supportContext(),
        catalog,
        exactMetadataPattern(context, schema),
        exactMetadataPattern(context, tableName),
        "%"
    );
    for (List<Object> row : metadata.rows()) {
      String columnName = stringValue(metadata, row, "COLUMN_NAME");
      if (columnName == null) {
        continue;
      }
      columns.add(new JdbcMetadataModels.ColumnDefinition(
          columnName,
          stringValue(metadata, row, "TYPE_NAME"),
          integerValue(metadata, row, "COLUMN_SIZE"),
          integerValue(metadata, row, "DECIMAL_DIGITS"),
          nullableValue(metadata, row, "NULLABLE"),
          stringValue(metadata, row, "COLUMN_DEF"),
          parseYesNo(stringValue(metadata, row, "IS_AUTOINCREMENT")),
          stringValue(metadata, row, "REMARKS")
      ));
    }
    return columns;
  }
}
