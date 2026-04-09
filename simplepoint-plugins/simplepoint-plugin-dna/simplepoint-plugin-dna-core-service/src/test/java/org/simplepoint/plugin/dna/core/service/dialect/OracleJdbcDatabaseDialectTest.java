package org.simplepoint.plugin.dna.core.service.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;

class OracleJdbcDatabaseDialectTest {

  @Test
  void shouldUseOracleSpecificMetadataAndAlterSql() {
    OracleJdbcDatabaseDialect dialect = new OracleJdbcDatabaseDialect();
    JdbcDatabaseDialect.SupportContext context = new JdbcDatabaseDialect.SupportContext(
        "oracle",
        "oracle.jdbc.OracleDriver",
        "Oracle",
        "21c",
        null,
        "APP",
        "\"",
        Map.of()
    );

    assertTrue(dialect.supports(context));
    assertNull(dialect.metadataCatalog("XE", context));
    assertFalse(dialect.supportsNamespaceCreate(JdbcMetadataModels.NodeType.SCHEMA));
    assertEquals(
        "SELECT * FROM \"APP\".\"USERS\" ORDER BY 1 OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY",
        dialect.buildPreviewSql("\"APP\".\"USERS\"", 20, 10)
    );
    assertEquals(
        "ALTER TABLE \"APP\".\"USERS\" ADD (\"EMAIL\" VARCHAR2(128) DEFAULT 'n/a')",
        dialect.buildAddColumnSql(
            "\"APP\".\"USERS\"",
            new JdbcMetadataModels.ColumnDefinition("EMAIL", "VARCHAR2", 128, null, true, "'n/a'", null, null),
            context
        )
    );
    assertEquals(
        List.of(
            "ALTER TABLE \"APP\".\"USERS\" RENAME COLUMN \"MAIL\" TO \"EMAIL\"",
            "ALTER TABLE \"APP\".\"USERS\" MODIFY (\"EMAIL\" VARCHAR2(128) DEFAULT 'n/a')"
        ),
        dialect.buildAlterColumnSql(
            "\"APP\".\"USERS\"",
            "MAIL",
            new JdbcMetadataModels.ColumnDefinition("EMAIL", "VARCHAR2", 128, null, true, "'n/a'", null, null),
            context
        )
    );
  }
}
