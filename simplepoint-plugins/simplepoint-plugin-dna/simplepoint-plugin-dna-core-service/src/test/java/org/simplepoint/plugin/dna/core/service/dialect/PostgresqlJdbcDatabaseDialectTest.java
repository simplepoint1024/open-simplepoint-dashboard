package org.simplepoint.plugin.dna.core.service.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;

class PostgresqlJdbcDatabaseDialectTest {

  @Test
  void shouldExposeAllCatalogsAndReconnectForDifferentDatabase() {
    PostgresqlJdbcDatabaseDialect dialect = new PostgresqlJdbcDatabaseDialect();
    JdbcDatabaseDialect.SupportContext context = new JdbcDatabaseDialect.SupportContext(
        "postgresql",
        "org.postgresql.Driver",
        "PostgreSQL",
        "16",
        "app_db",
        "public",
        "\"",
        Map.of()
    );

    assertEquals(List.of("app_db", "audit_db"), dialect.visibleCatalogs(List.of("app_db", "audit_db"), context));
    assertTrue(dialect.requiresCatalogConnection("audit_db", context));
    assertNull(dialect.metadataCatalog("audit_db", context));
    assertEquals(
        "jdbc:postgresql://localhost:5432/audit_db?ssl=false",
        dialect.remapJdbcUrlCatalog("jdbc:postgresql://localhost:5432/app_db?ssl=false", "audit_db", context)
    );
  }
}
