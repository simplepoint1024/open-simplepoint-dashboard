package org.simplepoint.plugin.dna.runtime.service.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
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
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationOperation;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalIndexDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Compares predicted recovery boundaries with real database state after every generated statement. */
class SchemaPlanCheckpointsDatabaseTest {
  @Test
  void postgresqlCheckpointsMatchInitialAndEvolutionStatements() throws Exception {
    verify(true);
  }

  @Test
  void mysqlCheckpointsMatchInitialAndEvolutionStatements() throws Exception {
    verify(false);
  }

  private void verify(boolean postgres) throws Exception {
    String prefix = "DNA_RUNTIME_TEST_" + (postgres ? "POSTGRES" : "MYSQL");
    String url = System.getenv(prefix + "_URL");
    Assumptions.assumeTrue(url != null && !url.isBlank(), "Disposable database is required");
    var source = new DriverManagerDataSource(url, System.getenv(prefix + "_USER"), System.getenv(prefix + "_PASSWORD"));
    var codec = new PhysicalSchemaSnapshotCodec(new ObjectMapper());
    JdbcDatabaseDialect core = postgres ? new PostgresqlJdbcDatabaseDialect() : new MysqlJdbcDatabaseDialect();
    RuntimeSchemaDialect dialect = postgres ? new PostgresqlRuntimeSchemaDialect(codec) : new MysqlRuntimeSchemaDialect(codec);
    String table = "sp_checkpoint_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = source.getConnection(); var statement = connection.createStatement()) {
      var metadata = connection.getMetaData();
      var context = new JdbcDatabaseDialect.SupportContext(core.code(), metadata.getDriverName(),
          metadata.getDatabaseProductName(), metadata.getDatabaseProductVersion(), connection.getCatalog(),
          connection.getSchema(), metadata.getIdentifierQuoteString(), Map.of());
      var database = new DatabaseIdentity(metadata.getDatabaseProductName(), metadata.getDatabaseProductVersion(),
          metadata.getDatabaseMajorVersion(), metadata.getDatabaseMinorVersion());
      var target = new PhysicalSchemaTarget("model", new RuntimeMetadataTarget("test",
          connection.getCatalog(), postgres ? connection.getSchema() : null, table));
      var original = new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(
          field("title", "title", "原名称", true, 80, null),
          field("obsolete", "obsolete", "待移除", true, 80, null)),
          List.of(new LogicalIndexDefinition("lookup", List.of("title"), false)));
      var next = new PhysicalSchemaDefinition(original.contractVersion(), List.of(
          field("title", "renamed_title", "新名称", false, 160, "默认值"),
          field("added", "added", "新字段", true, 100, "新增默认")),
          List.of(new LogicalIndexDefinition("lookup", List.of("renamed_title"), true)), null, true);
      try {
        var initial = dialect.planInitial(connection, core, context, database, target, "客户·顧客", original);
        executeAndCheck(connection, core, context, dialect, target, initial);
        // Existing NULL must be detected using the current column, before the rename is executed.
        statement.executeUpdate("INSERT INTO " + table
            + " (id,tenant_id,created_by,created_at,updated_by,updated_at) VALUES "
            + "('00000000-0000-0000-0000-000000000001','tenant-a','actor',CURRENT_TIMESTAMP,'actor',CURRENT_TIMESTAMP)");
        assertThatThrownBy(() -> dialect.planEvolution(connection, core, context, database, target, "客户·顧客", original, next))
            .isInstanceOfSatisfying(DnaRuntimeException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.SCHEMA_PRECHECK_FAILED));
        statement.executeUpdate("UPDATE " + table + " SET title = '客户·顧客'");
        var evolution = dialect.planEvolution(connection, core, context, database, target, "客户·顧客", original, next);
        assertThat(evolution.steps()).extracting(PlannedSchemaStep::operation).contains(
            SchemaMigrationOperation.RENAME_COLUMN, SchemaMigrationOperation.ALTER_COLUMN,
            SchemaMigrationOperation.DROP_COLUMN, SchemaMigrationOperation.ADD_COLUMN,
            SchemaMigrationOperation.DROP_INDEX, SchemaMigrationOperation.CREATE_INDEX,
            SchemaMigrationOperation.COMMENT_COLUMN);
        executeAndCheck(connection, core, context, dialect, target, evolution);
        try (var rows = statement.executeQuery("SELECT renamed_title,added FROM " + table)) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString(1)).isEqualTo("客户·顧客");
          assertThat(rows.getString(2)).isEqualTo("新增默认");
        }
        // Planning against an already-complete initial target has repeated no-op checkpoints.
        var repeated = dialect.planInitial(connection, core, context, database, target, "客户·顧客", next);
        assertThat(repeated.checkpointChecksums()).allMatch(repeated.targetSnapshot().checksum()::equals);
        var unchanged = dialect.planEvolution(connection, core, context, database, target, "客户·顧客", next, next);
        assertThat(unchanged.steps()).isEmpty();
        assertThat(unchanged.checkpointChecksums()).containsExactly(unchanged.targetSnapshot().checksum());
      } finally {
        statement.execute("DROP TABLE IF EXISTS " + table);
      }
    }
  }

  private static LogicalFieldDefinition field(String id, String code, String label, boolean nullable,
      int length, String defaultValue) {
    return new LogicalFieldDefinition(id, code, label, LogicalFieldType.STRING, nullable, length, null, null,
        defaultValue == null ? null : new LogicalValue(LogicalValueKind.STRING, defaultValue));
  }

  private static void executeAndCheck(Connection connection, JdbcDatabaseDialect core,
      JdbcDatabaseDialect.SupportContext context, RuntimeSchemaDialect dialect, PhysicalSchemaTarget target,
      InitialSchemaPlan plan) throws Exception {
    assertThat(plan.checkpointChecksums()).hasSize(plan.steps().size() + 1);
    assertThat(dialect.inspect(connection, core, context, target).checksum()).isEqualTo(plan.checkpointChecksums().getFirst());
    for (var step : plan.steps()) {
      try (var statement = connection.createStatement()) {
        statement.execute(step.statementSql());
      }
      assertThat(dialect.inspect(connection, core, context, target).checksum())
          .as("%s statement %s: %s", dialect.dialectId(), step.sequence(), step.operation())
          .isEqualTo(plan.checkpointChecksums().get(step.sequence()));
    }
  }
}
