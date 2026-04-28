package org.simplepoint.plugin.dna.core.service.impl;

import java.util.List;
import org.simplepoint.plugin.dna.core.api.repository.JdbcDriverDefinitionRepository;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.core.api.service.JdbcDdlOperationService;
import org.simplepoint.plugin.dna.core.api.service.JdbcDialectManagementService;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataRequests;
import org.springframework.stereotype.Service;

/**
 * Runtime JDBC DDL operation service implementation.
 */
@Service
public class JdbcDdlOperationServiceImpl extends AbstractJdbcMetadataService
    implements JdbcDdlOperationService {

  /**
   * Creates the DDL operation service implementation.
   *
   * @param dataSourceService datasource service
   * @param driverRepository driver repository
   * @param dialectManagementService dialect management service
   */
  public JdbcDdlOperationServiceImpl(
      final JdbcDataSourceDefinitionService dataSourceService,
      final JdbcDriverDefinitionRepository driverRepository,
      final JdbcDialectManagementService dialectManagementService
  ) {
    super(dataSourceService, driverRepository, dialectManagementService);
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
}
