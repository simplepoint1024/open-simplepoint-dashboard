package org.simplepoint.plugin.dna.core.service.dialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;

/**
 * JDBC dialect for PostgreSQL-compatible databases.
 */
public class PostgresqlJdbcDatabaseDialect extends AbstractJdbcDatabaseDialect {

  @Override
  public String code() {
    return "postgresql";
  }

  @Override
  public String name() {
    return "PostgreSQL";
  }

  @Override
  public String description() {
    return "PostgreSQL compatible dialect";
  }

  @Override
  public int order() {
    return 10;
  }

  @Override
  public boolean supports(final SupportContext context) {
    String databaseType = normalize(context.databaseType());
    String driverClassName = normalize(context.driverClassName());
    String productName = normalize(context.productName());
    return databaseType.contains("postgres")
        || driverClassName.contains("postgres")
        || productName.contains("postgres");
  }

  @Override
  public JdbcMetadataModels.NodeType catalogNodeType() {
    return JdbcMetadataModels.NodeType.DATABASE;
  }

  @Override
  public List<String> visibleCatalogs(final List<String> catalogs, final SupportContext context) {
    return catalogs;
  }

  @Override
  public String metadataCatalog(final String catalog, final SupportContext context) {
    return null;
  }

  @Override
  public boolean requiresCatalogConnection(
      final String targetCatalog,
      final SupportContext context
  ) {
    String normalizedTarget = trimToNull(targetCatalog);
    String currentCatalog = trimToNull(context.currentCatalog());
    return normalizedTarget != null && currentCatalog != null && !normalizedTarget.equals(currentCatalog);
  }

  @Override
  public String remapJdbcUrlCatalog(
      final String jdbcUrl,
      final String targetCatalog,
      final SupportContext context
  ) {
    String normalizedTarget = trimToNull(targetCatalog);
    String normalizedUrl = trimToNull(jdbcUrl);
    if (normalizedTarget == null || normalizedUrl == null) {
      return jdbcUrl;
    }
    String queryPart = "";
    int queryIndex = normalizedUrl.indexOf('?');
    if (queryIndex >= 0) {
      queryPart = normalizedUrl.substring(queryIndex);
      normalizedUrl = normalizedUrl.substring(0, queryIndex);
    }
    String prefix = "jdbc:postgresql:";
    if (!normalizedUrl.startsWith(prefix)) {
      return jdbcUrl;
    }
    if (normalizedUrl.startsWith(prefix + "//")) {
      int authorityEnd = normalizedUrl.indexOf('/', (prefix + "//").length());
      if (authorityEnd < 0) {
        return normalizedUrl + "/" + normalizedTarget + queryPart;
      }
      return normalizedUrl.substring(0, authorityEnd + 1) + normalizedTarget + queryPart;
    }
    return prefix + normalizedTarget + queryPart;
  }

  @Override
  public boolean supportsNamespaceCreate(final JdbcMetadataModels.NodeType type) {
    return JdbcMetadataModels.NodeType.DATABASE.equals(type) || JdbcMetadataModels.NodeType.SCHEMA.equals(type);
  }

  @Override
  public boolean supportsNamespaceDrop(final JdbcMetadataModels.NodeType type) {
    return JdbcMetadataModels.NodeType.DATABASE.equals(type) || JdbcMetadataModels.NodeType.SCHEMA.equals(type);
  }

  @Override
  public String buildCreateNamespaceSql(
      final JdbcMetadataModels.NodeType type,
      final String name
  ) {
    return buildCreateNamespaceSql(type, name, null);
  }

  @Override
  public String buildCreateNamespaceSql(
      final JdbcMetadataModels.NodeType type,
      final String name,
      final SupportContext context
  ) {
    if (JdbcMetadataModels.NodeType.DATABASE.equals(type)) {
      return "CREATE DATABASE " + quoteIdentifier(name, context);
    }
    return super.buildCreateNamespaceSql(type, name, context);
  }

  @Override
  public String buildDropNamespaceSql(
      final JdbcMetadataModels.NodeType type,
      final String name,
      final boolean cascade
  ) {
    return buildDropNamespaceSql(type, name, cascade, null);
  }

  @Override
  public String buildDropNamespaceSql(
      final JdbcMetadataModels.NodeType type,
      final String name,
      final boolean cascade,
      final SupportContext context
  ) {
    if (JdbcMetadataModels.NodeType.DATABASE.equals(type)) {
      return "DROP DATABASE " + quoteIdentifier(name, context);
    }
    return super.buildDropNamespaceSql(type, name, cascade, context);
  }

  @Override
  protected List<JdbcMetadataModels.ConstraintDefinition> loadCheckConstraints(
      final Connection connection,
      final SupportContext context,
      final String catalog,
      final String schema,
      final String tableName
  ) throws SQLException {
    String effectiveSchema = schema;
    if (effectiveSchema == null || effectiveSchema.isBlank()) {
      effectiveSchema = context.currentSchema();
    }
    if (effectiveSchema == null || effectiveSchema.isBlank()) {
      return List.of();
    }
    String sql = """
        select tc.constraint_name, cc.check_clause
        from information_schema.table_constraints tc
        join information_schema.check_constraints cc
          on tc.constraint_schema = cc.constraint_schema
         and tc.constraint_name = cc.constraint_name
        where tc.constraint_type = 'CHECK'
          and tc.table_schema = ?
          and tc.table_name = ?
        order by tc.constraint_name
        """;
    List<JdbcMetadataModels.ConstraintDefinition> constraints = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, effectiveSchema);
      statement.setString(2, tableName);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          constraints.add(new JdbcMetadataModels.ConstraintDefinition(
              resultSet.getString(1),
              JdbcMetadataModels.ConstraintType.CHECK,
              List.of(),
              null,
              resultSet.getString(2)
          ));
        }
      }
    }
    return constraints;
  }

  private static String normalize(final String value) {
    return value == null ? "" : value.trim().toLowerCase();
  }

  private static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
