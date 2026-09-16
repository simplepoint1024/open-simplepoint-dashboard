package org.simplepoint.plugin.dna.core.service.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;

class SqlServerJdbcDatabaseDialectTest {

  @Test
  void shouldBuildSqlServerSpecificNamespaceAndPreviewSql() {
    SqlServerJdbcDatabaseDialect dialect = new SqlServerJdbcDatabaseDialect();
    JdbcDatabaseDialect.SupportContext context = new JdbcDatabaseDialect.SupportContext(
        "sqlserver",
        "com.microsoft.sqlserver.jdbc.SQLServerDriver",
        "Microsoft SQL Server",
        "16",
        "master",
        "dbo",
        "[",
        Map.of()
    );

    assertTrue(dialect.supports(context));
    assertEquals(JdbcMetadataModels.NodeType.DATABASE, dialect.catalogNodeType());
    assertEquals("CREATE DATABASE [analytics]", dialect.buildCreateNamespaceSql(
        JdbcMetadataModels.NodeType.DATABASE,
        "analytics",
        context
    ));
    assertEquals(
        "SELECT * FROM [analytics].[dbo].[users] ORDER BY (SELECT 1) OFFSET 10 ROWS FETCH NEXT 5 ROWS ONLY",
        dialect.buildPreviewSql("[analytics].[dbo].[users]", 10, 5)
    );
    assertEquals(
        List.of(
            "EXEC sp_rename N'[analytics].[dbo].[users].[mail]', N'email', 'COLUMN'",
            "ALTER TABLE [analytics].[dbo].[users] ALTER COLUMN [email] nvarchar(128) NOT NULL",
            "ALTER TABLE [analytics].[dbo].[users] ADD DEFAULT 'n/a' FOR [email]"
        ),
        dialect.buildAlterColumnSql(
            "[analytics].[dbo].[users]",
            "mail",
            new JdbcMetadataModels.ColumnDefinition("email", "nvarchar", 128, null, false, "'n/a'", null, null),
            context
        )
    );
  }
}
