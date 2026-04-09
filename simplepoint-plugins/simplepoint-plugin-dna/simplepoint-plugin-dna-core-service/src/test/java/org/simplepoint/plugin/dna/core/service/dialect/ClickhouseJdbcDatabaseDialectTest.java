package org.simplepoint.plugin.dna.core.service.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;

class ClickhouseJdbcDatabaseDialectTest {

  @Test
  void shouldUseClickhouseDatabaseSemanticsAndDisableConstraints() {
    ClickhouseJdbcDatabaseDialect dialect = new ClickhouseJdbcDatabaseDialect();
    JdbcDatabaseDialect.SupportContext context = new JdbcDatabaseDialect.SupportContext(
        "clickhouse",
        "com.clickhouse.jdbc.ClickHouseDriver",
        "ClickHouse",
        "24",
        null,
        "analytics",
        "\"",
        Map.of()
    );

    assertTrue(dialect.supports(context));
    assertFalse(dialect.supportsConstraintManagement());
    assertEquals(List.of(), dialect.visibleCatalogs(List.of("default"), context));
    assertEquals(
        "\"analytics\".\"events\"",
        dialect.qualifyName("ignored", "analytics", "events", context)
    );
    assertEquals(
        "CREATE DATABASE \"analytics\"",
        dialect.buildCreateNamespaceSql(JdbcMetadataModels.NodeType.SCHEMA, "analytics", context)
    );
    assertEquals(
        "CREATE TABLE \"analytics\".\"events\" (\"id\" UInt64, \"name\" Nullable(String) DEFAULT 'n/a')"
            + " ENGINE = MergeTree() ORDER BY (\"id\")",
        dialect.buildCreateTableSql(
            "\"analytics\".\"events\"",
            List.of(
                new JdbcMetadataModels.ColumnDefinition("id", "UInt64", null, null, false, null, null, null),
                new JdbcMetadataModels.ColumnDefinition("name", "String", null, null, true, "'n/a'", null, null)
            ),
            List.of(new JdbcMetadataModels.ConstraintDefinition(
                "pk_events",
                JdbcMetadataModels.ConstraintType.PRIMARY_KEY,
                List.of("id"),
                null,
                null
            )),
            context
        )
    );
    assertEquals(
        List.of(
            "ALTER TABLE \"analytics\".\"events\" RENAME COLUMN \"old_name\" TO \"name\"",
            "ALTER TABLE \"analytics\".\"events\" MODIFY COLUMN \"name\" Nullable(String) DEFAULT 'n/a'"
        ),
        dialect.buildAlterColumnSql(
            "\"analytics\".\"events\"",
            "old_name",
            new JdbcMetadataModels.ColumnDefinition("name", "String", null, null, true, "'n/a'", null, null),
            context
        )
    );
  }
}
