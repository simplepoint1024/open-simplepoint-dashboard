package org.simplepoint.plugin.dna.runtime.service.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.MysqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.PostgresqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationOperation;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationRisk;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalIndexDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;

/** Real target-database verification. Set the documented environment variables to run. */
class RuntimeSchemaDialectIntegrationTest {

  private final PhysicalSchemaSnapshotCodec codec = new PhysicalSchemaSnapshotCodec(
      new ObjectMapper().findAndRegisterModules()
  );

  @Test
  void publishesAndReReadsPostgresqlTable() throws Exception {
    String url = environment("DNA_RUNTIME_TEST_POSTGRES_URL");
    Assumptions.assumeTrue(url != null, "PostgreSQL integration URL is not configured");
    verify(
        url,
        environmentOr("DNA_RUNTIME_TEST_POSTGRES_USER", "postgres"),
        environmentOr("DNA_RUNTIME_TEST_POSTGRES_PASSWORD", "postgres"),
        new PostgresqlJdbcDatabaseDialect(),
        new PostgresqlRuntimeSchemaDialect(codec),
        "dna_runtime",
        "public"
    );
  }

  @Test
  void publishesAndReReadsMysqlTable() throws Exception {
    String url = environment("DNA_RUNTIME_TEST_MYSQL_URL");
    Assumptions.assumeTrue(url != null, "MySQL integration URL is not configured");
    verify(
        url,
        environmentOr("DNA_RUNTIME_TEST_MYSQL_USER", "root"),
        environmentOr("DNA_RUNTIME_TEST_MYSQL_PASSWORD", "root"),
        new MysqlJdbcDatabaseDialect(),
        new MysqlRuntimeSchemaDialect(codec),
        "dna_runtime",
        ""
    );
  }

  private void verify(
      final String url,
      final String username,
      final String password,
      final JdbcDatabaseDialect coreDialect,
      final AbstractRuntimeSchemaDialect runtimeDialect,
      final String catalog,
      final String schema
  ) throws Exception {
    String table = "spf_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    try (Connection connection = DriverManager.getConnection(url, username, password)) {
      JdbcDatabaseDialect.SupportContext context = context(connection, coreDialect.code());
      DatabaseIdentity database = database(connection);
      PhysicalSchemaTarget target = new PhysicalSchemaTarget(
          UUID.randomUUID().toString(),
          new RuntimeMetadataTarget("integration", catalog, schema, table)
      );
      InitialSchemaPlan plan = runtimeDialect.planInitial(
          connection,
          coreDialect,
          context,
          database,
          target,
          "Runtime 集成测试表",
          targetSchema()
      );
      assertThat(plan.actualSnapshot().exists()).isFalse();
      try {
        for (PlannedSchemaStep step : plan.steps()) {
          try (Statement statement = connection.createStatement()) {
            statement.execute(step.statementSql());
          }
        }
        var actual = runtimeDialect.inspect(connection, coreDialect, context, target);
        assertThat(actual.checksum()).isEqualTo(plan.targetSnapshot().checksum());

        InitialSchemaPlan repeated = runtimeDialect.planInitial(
            connection,
            coreDialect,
            context,
            database,
            target,
            "Runtime 集成测试表",
            targetSchema()
        );
        assertThat(repeated.actualSnapshot().checksum())
            .isEqualTo(repeated.targetSnapshot().checksum());

        InitialSchemaPlan evolution = runtimeDialect.planEvolution(
            connection,
            coreDialect,
            context,
            database,
            target,
            "Runtime 集成测试表",
            targetSchema(),
            evolvedSchema()
        );
        assertThat(evolution.steps()).extracting(PlannedSchemaStep::operation)
            .contains(
                SchemaMigrationOperation.RENAME_COLUMN,
                SchemaMigrationOperation.ADD_COLUMN
        );
        for (PlannedSchemaStep step : evolution.steps()) {
          try (Statement statement = connection.createStatement()) {
            statement.execute(step.statementSql());
          }
        }
        assertThat(runtimeDialect.inspect(connection, coreDialect, context, target).checksum())
            .isEqualTo(evolution.targetSnapshot().checksum());

        InitialSchemaPlan destructive = runtimeDialect.planEvolution(
            connection,
            coreDialect,
            context,
            database,
            target,
            "Runtime 集成测试表",
            evolvedSchema(),
            targetSchema()
        );
        assertThat(destructive.risk()).isEqualTo(SchemaMigrationRisk.DESTRUCTIVE);
        assertThat(destructive.steps()).extracting(PlannedSchemaStep::operation)
            .contains(SchemaMigrationOperation.DROP_COLUMN);
      } finally {
        try (Statement statement = connection.createStatement()) {
          statement.execute("DROP TABLE " + runtimeDialect.qualifiedTable(
              coreDialect,
              context,
              target
          ));
        }
      }
    }
  }

  private static PhysicalSchemaDefinition targetSchema() {
    return new PhysicalSchemaDefinition(
        DnaRuntimeApiVersion.CURRENT,
        List.of(
            field(
                "title",
                LogicalFieldType.STRING,
                false,
                128,
                null,
                null,
                new LogicalValue(LogicalValueKind.STRING, "draft")
            ),
            field(
                "amount",
                LogicalFieldType.DECIMAL,
                true,
                null,
                18,
                2,
                new LogicalValue(LogicalValueKind.DECIMAL, "1.20")
            ),
            field(
                "enabled",
                LogicalFieldType.BOOLEAN,
                false,
                null,
                null,
                null,
                new LogicalValue(LogicalValueKind.BOOLEAN, "true")
            ),
            field("notes", LogicalFieldType.TEXT, true, null, null, null, null),
            field("payload", LogicalFieldType.JSON, true, null, null, null, null),
            field("attachment", LogicalFieldType.BINARY, true, null, null, null, null),
            field("external_id", LogicalFieldType.UUID, true, null, null, null, null),
            field("due_date", LogicalFieldType.DATE, true, null, null, null, null),
            field("event_time", LogicalFieldType.TIME, true, null, null, null, null),
            field("occurred_at", LogicalFieldType.DATETIME, true, null, null, null, null)
        ),
        List.of(
            new LogicalIndexDefinition("title_unique", List.of("title"), true),
            new LogicalIndexDefinition("enabled_amount", List.of("enabled", "amount"), false)
        )
    );
  }

  private static PhysicalSchemaDefinition evolvedSchema() {
    return new PhysicalSchemaDefinition(
        DnaRuntimeApiVersion.CURRENT,
        List.of(
            new LogicalFieldDefinition(
                "field-title",
                "subject",
                "字段 subject",
                LogicalFieldType.STRING,
                false,
                128,
                null,
                null,
                new LogicalValue(LogicalValueKind.STRING, "draft")
            ),
            field(
                "amount",
                LogicalFieldType.DECIMAL,
                true,
                null,
                18,
                2,
                new LogicalValue(LogicalValueKind.DECIMAL, "1.20")
            ),
            field(
                "enabled",
                LogicalFieldType.BOOLEAN,
                false,
                null,
                null,
                null,
                new LogicalValue(LogicalValueKind.BOOLEAN, "true")
            ),
            field("notes", LogicalFieldType.TEXT, true, null, null, null, null),
            field("payload", LogicalFieldType.JSON, true, null, null, null, null),
            field("attachment", LogicalFieldType.BINARY, true, null, null, null, null),
            field("external_id", LogicalFieldType.UUID, true, null, null, null, null),
            field("due_date", LogicalFieldType.DATE, true, null, null, null, null),
            field("event_time", LogicalFieldType.TIME, true, null, null, null, null),
            field("occurred_at", LogicalFieldType.DATETIME, true, null, null, null, null),
            field("priority", LogicalFieldType.INT32, true, null, null, null, null)
        ),
        List.of(
            new LogicalIndexDefinition("title_unique", List.of("subject"), true),
            new LogicalIndexDefinition("enabled_amount", List.of("enabled", "amount"), false),
            new LogicalIndexDefinition("priority", List.of("priority"), false)
        )
    );
  }

  private static LogicalFieldDefinition field(
      final String code,
      final LogicalFieldType type,
      final boolean nullable,
      final Integer length,
      final Integer precision,
      final Integer scale,
      final LogicalValue defaultValue
  ) {
    return new LogicalFieldDefinition(
        "field-" + code,
        code,
        "字段 " + code,
        type,
        nullable,
        length,
        precision,
        scale,
        defaultValue
    );
  }

  private static JdbcDatabaseDialect.SupportContext context(
      final Connection connection,
      final String databaseType
  ) throws Exception {
    DatabaseMetaData metadata = connection.getMetaData();
    return new JdbcDatabaseDialect.SupportContext(
        databaseType,
        metadata.getDriverName(),
        metadata.getDatabaseProductName(),
        metadata.getDatabaseProductVersion(),
        connection.getCatalog(),
        connection.getSchema(),
        metadata.getIdentifierQuoteString(),
        Map.of()
    );
  }

  private static DatabaseIdentity database(final Connection connection) throws Exception {
    DatabaseMetaData metadata = connection.getMetaData();
    return new DatabaseIdentity(
        metadata.getDatabaseProductName(),
        metadata.getDatabaseProductVersion(),
        metadata.getDatabaseMajorVersion(),
        metadata.getDatabaseMinorVersion()
    );
  }

  private static String environment(final String name) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String environmentOr(final String name, final String fallback) {
    String value = environment(name);
    return value == null ? fallback : value;
  }
}
