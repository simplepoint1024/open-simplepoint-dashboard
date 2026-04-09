package org.simplepoint.plugin.dna.core.api.service;

import java.util.List;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataRequests;
import org.springframework.data.domain.Pageable;

/**
 * Service contract for runtime JDBC metadata browsing, DDL operations, and paged data preview.
 */
public interface JdbcMetadataManagementService {

  /**
   * Lists metadata tree children for the supplied datasource and parent path.
   *
   * @param dataSourceId datasource id
   * @param request parent path request
   * @return child tree nodes
   */
  List<JdbcMetadataModels.TreeNode> children(String dataSourceId, JdbcMetadataRequests.PathRequest request);

  /**
   * Returns detailed table/view structure information.
   *
   * @param dataSourceId datasource id
   * @param request table path request
   * @return structure details
   */
  JdbcMetadataModels.TableStructure structure(String dataSourceId, JdbcMetadataRequests.PathRequest request);

  /**
   * Returns paged table/view data preview.
   *
   * @param dataSourceId datasource id
   * @param request table path request
   * @param pageable paging arguments
   * @return preview page
   */
  JdbcMetadataModels.DataPreviewPage preview(
      String dataSourceId,
      JdbcMetadataRequests.PathRequest request,
      Pageable pageable
  );

  /**
   * Creates a namespace such as a database or schema.
   *
   * @param dataSourceId datasource id
   * @param request create request
   */
  void createNamespace(String dataSourceId, JdbcMetadataRequests.NamespaceCreateRequest request);

  /**
   * Drops a namespace, table, or view.
   *
   * @param dataSourceId datasource id
   * @param request drop request
   */
  void drop(String dataSourceId, JdbcMetadataRequests.DropRequest request);

  /**
   * Creates a table.
   *
   * @param dataSourceId datasource id
   * @param request create request
   */
  void createTable(String dataSourceId, JdbcMetadataRequests.TableCreateRequest request);

  /**
   * Creates a view.
   *
   * @param dataSourceId datasource id
   * @param request create request
   */
  void createView(String dataSourceId, JdbcMetadataRequests.ViewCreateRequest request);

  /**
   * Adds a column to a table.
   *
   * @param dataSourceId datasource id
   * @param request add request
   */
  void addColumn(String dataSourceId, JdbcMetadataRequests.ColumnAddRequest request);

  /**
   * Alters a column definition.
   *
   * @param dataSourceId datasource id
   * @param request alter request
   */
  void alterColumn(String dataSourceId, JdbcMetadataRequests.ColumnAlterRequest request);

  /**
   * Drops a column.
   *
   * @param dataSourceId datasource id
   * @param request drop request
   */
  void dropColumn(String dataSourceId, JdbcMetadataRequests.ColumnDropRequest request);

  /**
   * Adds a constraint to a table.
   *
   * @param dataSourceId datasource id
   * @param request add request
   */
  void addConstraint(String dataSourceId, JdbcMetadataRequests.ConstraintAddRequest request);

  /**
   * Drops a constraint from a table.
   *
   * @param dataSourceId datasource id
   * @param request drop request
   */
  void dropConstraint(String dataSourceId, JdbcMetadataRequests.ConstraintDropRequest request);
}
