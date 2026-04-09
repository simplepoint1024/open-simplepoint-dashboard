package org.simplepoint.plugin.dna.core.api.vo;

import java.util.List;

/**
 * Shared request models for metadata tree browsing and DDL actions.
 */
public final class JdbcMetadataRequests {

  private JdbcMetadataRequests() {
  }

  /**
   * Metadata path request.
   *
   * @param path metadata path
   */
  public record PathRequest(
      List<JdbcMetadataModels.PathSegment> path
  ) {
  }

  /**
   * Namespace creation request.
   *
   * @param type namespace type
   * @param parentPath parent path
   * @param name namespace name
   */
  public record NamespaceCreateRequest(
      JdbcMetadataModels.NodeType type,
      List<JdbcMetadataModels.PathSegment> parentPath,
      String name
  ) {
  }

  /**
   * Generic drop request.
   *
   * @param path target path
   * @param cascade whether to cascade
   */
  public record DropRequest(
      List<JdbcMetadataModels.PathSegment> path,
      Boolean cascade
  ) {
  }

  /**
   * Table creation request.
   *
   * @param parentPath parent path
   * @param name table name
   * @param columns columns
   * @param constraints constraints
   */
  public record TableCreateRequest(
      List<JdbcMetadataModels.PathSegment> parentPath,
      String name,
      List<JdbcMetadataModels.ColumnDefinition> columns,
      List<JdbcMetadataModels.ConstraintDefinition> constraints
  ) {
  }

  /**
   * View creation request.
   *
   * @param parentPath parent path
   * @param name view name
   * @param definitionSql view SQL
   */
  public record ViewCreateRequest(
      List<JdbcMetadataModels.PathSegment> parentPath,
      String name,
      String definitionSql
  ) {
  }

  /**
   * Column add request.
   *
   * @param tablePath target table path
   * @param column column definition
   */
  public record ColumnAddRequest(
      List<JdbcMetadataModels.PathSegment> tablePath,
      JdbcMetadataModels.ColumnDefinition column
  ) {
  }

  /**
   * Column alter request.
   *
   * @param tablePath target table path
   * @param currentName current column name
   * @param column target column definition
   */
  public record ColumnAlterRequest(
      List<JdbcMetadataModels.PathSegment> tablePath,
      String currentName,
      JdbcMetadataModels.ColumnDefinition column
  ) {
  }

  /**
   * Column drop request.
   *
   * @param tablePath target table path
   * @param columnName column name
   */
  public record ColumnDropRequest(
      List<JdbcMetadataModels.PathSegment> tablePath,
      String columnName
  ) {
  }

  /**
   * Constraint add request.
   *
   * @param tablePath target table path
   * @param constraint constraint definition
   */
  public record ConstraintAddRequest(
      List<JdbcMetadataModels.PathSegment> tablePath,
      JdbcMetadataModels.ConstraintDefinition constraint
  ) {
  }

  /**
   * Constraint drop request.
   *
   * @param tablePath target table path
   * @param constraintName constraint name
   * @param type constraint type
   */
  public record ConstraintDropRequest(
      List<JdbcMetadataModels.PathSegment> tablePath,
      String constraintName,
      JdbcMetadataModels.ConstraintType type
  ) {
  }
}
