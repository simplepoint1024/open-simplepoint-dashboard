package org.simplepoint.plugin.dna.core.service.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;

class DamengJdbcDatabaseDialectTest {

  @Test
  void shouldMatchDmDriverAndUseModifySyntax() {
    DamengJdbcDatabaseDialect dialect = new DamengJdbcDatabaseDialect();
    JdbcDatabaseDialect.SupportContext context = new JdbcDatabaseDialect.SupportContext(
        "dm",
        "dm.jdbc.driver.DmDriver",
        "DM DBMS",
        "8",
        null,
        "SYSDBA",
        "\"",
        Map.of()
    );

    assertTrue(dialect.supports(context));
    assertEquals(
        "ALTER TABLE \"SYSDBA\".\"USERS\" ADD \"EMAIL\" VARCHAR(128) DEFAULT 'n/a'",
        dialect.buildAddColumnSql(
            "\"SYSDBA\".\"USERS\"",
            new JdbcMetadataModels.ColumnDefinition("EMAIL", "VARCHAR", 128, null, true, "'n/a'", null, null),
            context
        )
    );
    assertEquals(
        List.of(
            "ALTER TABLE \"SYSDBA\".\"USERS\" RENAME COLUMN \"MAIL\" TO \"EMAIL\"",
            "ALTER TABLE \"SYSDBA\".\"USERS\" MODIFY \"EMAIL\" VARCHAR(128) DEFAULT 'n/a'"
        ),
        dialect.buildAlterColumnSql(
            "\"SYSDBA\".\"USERS\"",
            "MAIL",
            new JdbcMetadataModels.ColumnDefinition("EMAIL", "VARCHAR", 128, null, true, "'n/a'", null, null),
            context
        )
    );
  }
}
