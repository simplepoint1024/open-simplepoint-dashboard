package org.simplepoint.plugin.dna.core.service.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;

class PostgresqlJdbcDatabaseDialectTest {

  private final PostgresqlJdbcDatabaseDialect dialect = new PostgresqlJdbcDatabaseDialect();

  private static JdbcDatabaseDialect.SupportContext context(
      final String catalog, final String quoteChar
  ) {
    return new JdbcDatabaseDialect.SupportContext(
        "postgresql", "org.postgresql.Driver", "PostgreSQL", "16",
        catalog, "public", quoteChar, Map.of()
    );
  }

  // ---- basic identity ----

  @Test
  void shouldExposeCorrectCodeAndName() {
    assertEquals("postgresql", dialect.code());
    assertEquals("PostgreSQL", dialect.name());
    assertEquals(10, dialect.order());
  }

  @Test
  void shouldReturnDatabaseAsCatalogNodeType() {
    assertEquals(JdbcMetadataModels.NodeType.DATABASE, dialect.catalogNodeType());
  }

  // ---- supports ----

  @Test
  void shouldSupportPostgresqlDatabaseType() {
    assertTrue(dialect.supports(new JdbcDatabaseDialect.SupportContext(
        "postgresql", "some.Driver", null, null, null, null, null, Map.of()
    )));
  }

  @Test
  void shouldSupportPostgresqlDriverClassName() {
    assertTrue(dialect.supports(new JdbcDatabaseDialect.SupportContext(
        "other", "org.postgresql.Driver", null, null, null, null, null, Map.of()
    )));
  }

  @Test
  void shouldSupportPostgresqlProductName() {
    assertTrue(dialect.supports(new JdbcDatabaseDialect.SupportContext(
        "other", "other.Driver", "PostgreSQL", null, null, null, null, Map.of()
    )));
  }

  @Test
  void shouldNotSupportMysqlContext() {
    assertFalse(dialect.supports(new JdbcDatabaseDialect.SupportContext(
        "mysql", "com.mysql.cj.jdbc.Driver", "MySQL", null, null, null, null, Map.of()
    )));
  }

  // ---- catalogs and connection ----

  @Test
  void shouldExposeAllCatalogsAndReconnectForDifferentDatabase() {
    JdbcDatabaseDialect.SupportContext ctx = context("app_db", "\"");

    assertEquals(List.of("app_db", "audit_db"), dialect.visibleCatalogs(List.of("app_db", "audit_db"), ctx));
    assertTrue(dialect.requiresCatalogConnection("audit_db", ctx));
    assertNull(dialect.metadataCatalog("audit_db", ctx));
    assertEquals(
        "jdbc:postgresql://localhost:5432/audit_db?ssl=false",
        dialect.remapJdbcUrlCatalog("jdbc:postgresql://localhost:5432/app_db?ssl=false", "audit_db", ctx)
    );
  }

  @Test
  void requiresCatalogConnectionShouldReturnFalseWhenSameCatalog() {
    JdbcDatabaseDialect.SupportContext ctx = context("mydb", "\"");
    assertFalse(dialect.requiresCatalogConnection("mydb", ctx));
  }

  @Test
  void requiresCatalogConnectionShouldReturnFalseWhenTargetNull() {
    JdbcDatabaseDialect.SupportContext ctx = context("mydb", "\"");
    assertFalse(dialect.requiresCatalogConnection(null, ctx));
  }

  // ---- remapJdbcUrlCatalog ----

  @Test
  void remapJdbcUrlCatalogShouldHandleUrlWithoutQueryString() {
    JdbcDatabaseDialect.SupportContext ctx = context("app_db", "\"");
    assertEquals(
        "jdbc:postgresql://localhost:5432/new_db",
        dialect.remapJdbcUrlCatalog("jdbc:postgresql://localhost:5432/app_db", "new_db", ctx)
    );
  }

  @Test
  void remapJdbcUrlCatalogShouldReturnOriginalWhenUrlIsNull() {
    JdbcDatabaseDialect.SupportContext ctx = context("app_db", "\"");
    assertNull(dialect.remapJdbcUrlCatalog(null, "new_db", ctx));
  }

  @Test
  void remapJdbcUrlCatalogShouldReturnOriginalWhenTargetIsNull() {
    JdbcDatabaseDialect.SupportContext ctx = context("app_db", "\"");
    assertEquals(
        "jdbc:postgresql://localhost:5432/app_db",
        dialect.remapJdbcUrlCatalog("jdbc:postgresql://localhost:5432/app_db", null, ctx)
    );
  }

  @Test
  void remapJdbcUrlCatalogShouldReturnOriginalWhenNotPostgresUrl() {
    JdbcDatabaseDialect.SupportContext ctx = context("app_db", "\"");
    assertEquals(
        "jdbc:mysql://localhost:3306/app_db",
        dialect.remapJdbcUrlCatalog("jdbc:mysql://localhost:3306/app_db", "new_db", ctx)
    );
  }

  // ---- namespace support ----

  @Test
  void shouldSupportCreateAndDropForDatabaseAndSchema() {
    assertTrue(dialect.supportsNamespaceCreate(JdbcMetadataModels.NodeType.DATABASE));
    assertTrue(dialect.supportsNamespaceCreate(JdbcMetadataModels.NodeType.SCHEMA));
    assertFalse(dialect.supportsNamespaceCreate(JdbcMetadataModels.NodeType.TABLE));
    assertTrue(dialect.supportsNamespaceDrop(JdbcMetadataModels.NodeType.DATABASE));
    assertTrue(dialect.supportsNamespaceDrop(JdbcMetadataModels.NodeType.SCHEMA));
  }

  @Test
  void buildCreateNamespaceSqlShouldGenerateCreateDatabaseSql() {
    JdbcDatabaseDialect.SupportContext ctx = context("app_db", "\"");
    assertEquals(
        "CREATE DATABASE \"new_db\"",
        dialect.buildCreateNamespaceSql(JdbcMetadataModels.NodeType.DATABASE, "new_db", ctx)
    );
  }

  @Test
  void buildDropNamespaceSqlShouldGenerateDropDatabaseSql() {
    JdbcDatabaseDialect.SupportContext ctx = context("app_db", "\"");
    assertEquals(
        "DROP DATABASE \"old_db\"",
        dialect.buildDropNamespaceSql(JdbcMetadataModels.NodeType.DATABASE, "old_db", true, ctx)
    );
  }

  // ---- loadCheckConstraints ----

  @Test
  void loadCheckConstraintsShouldReturnEmptyWhenNoSchema() throws Exception {
    JdbcDatabaseDialect.SupportContext ctx = new JdbcDatabaseDialect.SupportContext(
        "postgresql", "org.postgresql.Driver", "PostgreSQL", "16",
        "mydb", null, "\"", Map.of()
    );
    Connection connection = mock(Connection.class);

    // No schema in context or params → empty
    List<JdbcMetadataModels.ConstraintDefinition> result =
        dialect.loadCheckConstraints(connection, ctx, "mydb", null, "users");
    assertTrue(result.isEmpty());
  }

  @Test
  void loadCheckConstraintsShouldQueryAndReturnResults() throws Exception {
    JdbcDatabaseDialect.SupportContext ctx = context("mydb", "\"");
    Connection connection = mock(Connection.class);
    PreparedStatement stmt = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenReturn(stmt);
    when(stmt.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString(1)).thenReturn("chk_score");
    when(rs.getString(2)).thenReturn("score > 0");

    List<JdbcMetadataModels.ConstraintDefinition> result =
        dialect.loadCheckConstraints(connection, ctx, "mydb", "public", "scores");
    assertEquals(1, result.size());
    assertEquals("chk_score", result.get(0).name());
    assertEquals(JdbcMetadataModels.ConstraintType.CHECK, result.get(0).type());
    assertEquals("score > 0", result.get(0).checkExpression());
  }

  // ---- type mapping ----

  @Test
  void typeMappingShouldResolvePostgresqlSpecificTypes() {
    assertEquals(java.sql.Types.OTHER,
        dialect.typeMapping().resolveJdbcType("JSONB", java.sql.Types.OTHER));
    assertEquals(java.sql.Types.ARRAY,
        dialect.typeMapping().resolveJdbcType("_TEXT", java.sql.Types.ARRAY));
    assertEquals(java.sql.Types.OTHER,
        dialect.typeMapping().resolveJdbcType("UUID", java.sql.Types.OTHER));
  }
}
