package org.simplepoint.plugin.dna.core.api.service;

import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataRequests;

/**
 * Service contract for runtime JDBC DDL operations.
 */
public interface JdbcDdlOperationService {

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
