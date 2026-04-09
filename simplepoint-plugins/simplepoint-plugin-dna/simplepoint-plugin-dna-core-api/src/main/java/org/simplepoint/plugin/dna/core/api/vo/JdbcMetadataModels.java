package org.simplepoint.plugin.dna.core.api.vo;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Shared metadata and dialect response models used by the DNA metadata feature.
 */
public final class JdbcMetadataModels {

  private JdbcMetadataModels() {
  }

  /**
   * Metadata tree node type.
   */
  public enum NodeType {
    ROOT,
    DATABASE,
    CATALOG,
    SCHEMA,
    TABLE,
    VIEW,
    COLUMN
  }

  /**
   * Supported metadata actions surfaced to the frontend.
   */
  public enum Action {
    CREATE_NAMESPACE,
    CREATE_TABLE,
    CREATE_VIEW,
    DROP_OBJECT,
    PREVIEW_DATA,
    ADD_COLUMN,
    ALTER_COLUMN,
    DROP_COLUMN,
    ADD_CONSTRAINT,
    DROP_CONSTRAINT,
    REFRESH
  }

  /**
   * Supported table constraint types.
   */
  public enum ConstraintType {
    PRIMARY_KEY,
    FOREIGN_KEY,
    UNIQUE,
    CHECK
  }

  /**
   * Dialect source type.
   */
  public enum SourceType {
    CLASSPATH,
    URL,
    UPLOAD
  }

  /**
   * Path segment identifying a metadata node.
   *
   * @param type node type
   * @param name node name
   */
  public record PathSegment(
      NodeType type,
      String name
  ) {
  }

  /**
   * Metadata tree node.
   *
   * @param key node key
   * @param title node title
   * @param type node type
   * @param typeLabel node type label
   * @param path full metadata path
   * @param leaf whether this node is a leaf
   * @param actions supported actions
   * @param dataType column data type
   * @param nullable column nullable flag
   * @param defaultValue column default value
   * @param remarks comments/remarks
   */
  public record TreeNode(
      String key,
      String title,
      NodeType type,
      String typeLabel,
      List<PathSegment> path,
      boolean leaf,
      List<Action> actions,
      String dataType,
      Boolean nullable,
      String defaultValue,
      String remarks
  ) {
  }

  /**
   * Column definition or column metadata snapshot.
   *
   * @param name column name
   * @param typeName SQL type name
   * @param size length or precision
   * @param scale decimal scale
   * @param nullable nullable flag
   * @param defaultValue default value
   * @param autoIncrement auto-increment flag
   * @param remarks remarks/comment
   */
  public record ColumnDefinition(
      String name,
      String typeName,
      Integer size,
      Integer scale,
      Boolean nullable,
      String defaultValue,
      Boolean autoIncrement,
      String remarks
  ) {
  }

  /**
   * Reference target for foreign-key constraints.
   *
   * @param tablePath referenced table path
   * @param columns referenced columns
   */
  public record ConstraintReference(
      List<PathSegment> tablePath,
      List<String> columns
  ) {
  }

  /**
   * Constraint definition or structure snapshot.
   *
   * @param name constraint name
   * @param type constraint type
   * @param columns local columns
   * @param reference foreign-key reference
   * @param checkExpression check expression
   */
  public record ConstraintDefinition(
      String name,
      ConstraintType type,
      List<String> columns,
      ConstraintReference reference,
      String checkExpression
  ) {
  }

  /**
   * Full table/view structure payload.
   *
   * @param tablePath table/view path
   * @param dialectCode resolved dialect code
   * @param dialectName resolved dialect name
   * @param columns columns
   * @param constraints constraints
   */
  public record TableStructure(
      List<PathSegment> tablePath,
      String dialectCode,
      String dialectName,
      List<ColumnDefinition> columns,
      List<ConstraintDefinition> constraints
  ) {
  }

  /**
   * Paged data preview result.
   *
   * @param columns ordered column names
   * @param content row data
   * @param totalElements total rows
   * @param pageNumber zero-based page number
   * @param pageSize page size
   */
  public record DataPreviewPage(
      List<String> columns,
      List<Map<String, Object>> content,
      long totalElements,
      int pageNumber,
      int pageSize
  ) {
  }

  /**
   * Loaded dialect descriptor shown in dialect management.
   *
   * @param code dialect code
   * @param name dialect name
   * @param description description
   * @param version version
   * @param className implementation class
   * @param sourceType source type
   * @param sourceId external source id
   * @param sourceName source name
   * @param sourceUrl source URL
   * @param localJarPath local jar path
   * @param enabled enabled flag
   * @param lastLoadMessage last load message
   * @param loadedAt last load time
   * @param boundDriverCodes automatically bound driver codes
   * @param order dialect order
   */
  public record DialectDescriptor(
      String code,
      String name,
      String description,
      String version,
      String className,
      SourceType sourceType,
      String sourceId,
      String sourceName,
      String sourceUrl,
      String localJarPath,
      Boolean enabled,
      String lastLoadMessage,
      Instant loadedAt,
      List<String> boundDriverCodes,
      int order
  ) {
  }

  /**
   * External dialect source summary.
   *
   * @param id source id
   * @param name source name
   * @param sourceType source type
   * @param sourceUrl source URL
   * @param localJarPath local jar path
   * @param enabled enabled flag
   * @param description description
   * @param loadedAt last load time
   * @param lastLoadMessage last load message
   * @param discoveredDialectCodes discovered dialect codes
   */
  public record DialectSourceSummary(
      String id,
      String name,
      SourceType sourceType,
      String sourceUrl,
      String localJarPath,
      Boolean enabled,
      String description,
      Instant loadedAt,
      String lastLoadMessage,
      List<String> discoveredDialectCodes
  ) {
  }
}
