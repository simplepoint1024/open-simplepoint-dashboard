package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.MysqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.PostgresqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.JdbcPhysicalSchemaBackend;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;
import org.simplepoint.plugin.dna.runtime.service.schema.MysqlRuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaSnapshotCodec;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.schema.PostgresqlRuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordWriteFenceProvisioner;
import org.simplepoint.plugin.dna.runtime.service.schema.RuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;

/** Real PostgreSQL/MySQL Calcite DML and physical transaction verification. */
class RuntimeCalciteRecordBackendDatabaseIntegrationTest {

  @Test
  void executesAndRollsBackOnPostgresql() throws Exception {
    String url = environment("DNA_RUNTIME_TEST_POSTGRES_URL");
    Assumptions.assumeTrue(url != null, "PostgreSQL integration URL is not configured");
    verify(
        url,
        environmentOr("DNA_RUNTIME_TEST_POSTGRES_USER", "postgres"),
        environmentOr("DNA_RUNTIME_TEST_POSTGRES_PASSWORD", "postgres"),
        null,
        "public"
    );
  }

  @Test
  void executesAndRollsBackOnMysql() throws Exception {
    String url = environment("DNA_RUNTIME_TEST_MYSQL_URL");
    Assumptions.assumeTrue(url != null, "MySQL integration URL is not configured");
    verify(
        url,
        environmentOr("DNA_RUNTIME_TEST_MYSQL_USER", "root"),
        environmentOr("DNA_RUNTIME_TEST_MYSQL_PASSWORD", "root"),
        "dna_runtime",
        null
    );
  }

  private static void verify(
      final String url,
      final String username,
      final String password,
      final String catalog,
      final String schema
  ) throws Exception {
    String table = "spf_dml_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    boolean postgresql = schema != null;
    String idType = "VARCHAR(36)";
    String timestampType = postgresql ? "TIMESTAMP(6) WITH TIME ZONE" : "DATETIME(6)";
    SimpleDataSource dataSource = new SimpleDataSource(
        new DriverManagerDataSource(url, username, password)
    );
    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE " + table + " ("
          + "id " + idType + " PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, "
          + "record_version BIGINT NOT NULL, created_by VARCHAR(64), "
          + "created_org_dept_id VARCHAR(64), created_at " + timestampType + ", "
          + "updated_by VARCHAR(64), updated_at " + timestampType + ", "
          + "deleted_at " + timestampType + ", title VARCHAR(128) NOT NULL)"
      );
    }
    RuntimeRecordModel model = model(table, catalog, schema);
    PhysicalSchemaSnapshotCodec snapshotCodec = new PhysicalSchemaSnapshotCodec(
        new ObjectMapper().findAndRegisterModules()
    );
    RuntimeSchemaDialect runtimeDialect = postgresql
        ? new PostgresqlRuntimeSchemaDialect(snapshotCodec)
        : new MysqlRuntimeSchemaDialect(snapshotCodec);
    JdbcDatabaseDialect core = postgresql ? new PostgresqlJdbcDatabaseDialect() : new MysqlJdbcDatabaseDialect();
    JdbcDatabaseDialect.SupportContext context;
    DatabaseIdentity database;
    try (Connection connection = dataSource.getConnection()) {
      var metadata = connection.getMetaData();
      context = new JdbcDatabaseDialect.SupportContext(core.code(), metadata.getDriverName(), metadata.getDatabaseProductName(),
          metadata.getDatabaseProductVersion(), catalog, schema, metadata.getIdentifierQuoteString(), Map.of());
      database = new DatabaseIdentity(metadata.getDatabaseProductName(), metadata.getDatabaseProductVersion(),
          metadata.getDatabaseMajorVersion(), metadata.getDatabaseMinorVersion());
    }
    ManagedRuntimeDataSourceProvider provider = mock(ManagedRuntimeDataSourceProvider.class);
    when(provider.open(any())).thenReturn(new ManagedRuntimeDataSource(
        dataSource,
        false,
        context,
        core,
        runtimeDialect,
        database,
        catalog,
        schema
    ));
    RuntimeCalciteRecordBackend backend = new RuntimeCalciteRecordBackend(
        provider,
        new DefaultCalciteQueryEngine()
    );
    new RecordWriteFencePreparation(new RecordWriteFenceProvisioner(new JdbcPhysicalSchemaBackend(provider, snapshotCodec)),
        backend).prepare(new RuntimeActor("tenant-a", "publisher"), model.target());
    try {
      String committedId = UUID.randomUUID().toString();
      RecordBatchExecutionResult committed = backend.execute(
          model, new RecordWriteAdmission("tenant-a", 0),
          List.of(insert(table, committedId)),
          15_000
      );
      assertThat(committed.affectedRows()).containsExactly(1);
      assertThat(committed.pushedStatementCount()).isGreaterThan(0);

      assertThatThrownBy(() -> backend.execute(
          model, new RecordWriteAdmission("tenant-a", 0),
          List.of(
              insert(table, UUID.randomUUID().toString()),
              updateMissing(table, UUID.randomUUID().toString())
          ),
          15_000
      )).isInstanceOf(RecordMutationMissException.class);
      try (Connection connection = dataSource.getConnection();
           Statement statement = connection.createStatement();
           ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
        result.next();
        assertThat(result.getLong(1)).isEqualTo(1L);
      }

      RecordSqlCompiler compiler = new RecordSqlCompiler(
          new StandardLogicalValueConverter()
      );
      RecordAccessContext access = access();
      RecordSqlStatement created = compiler.mutation(
          model,
          access,
          new RecordCreateMutation("create", Map.of("title", "service-create")),
          Instant.parse("2026-09-30T01:02:03.456Z")
      );
      backend.execute(model, new RecordWriteAdmission("tenant-a", 0), List.of(created), 15_000);
      backend.execute(model, new RecordWriteAdmission("tenant-a", 0), List.of(compiler.mutation(
          model,
          access,
          new RecordUpdateMutation(
              "update",
              created.recordId(),
              0,
              Map.of("title", "service-update")
          ),
          Instant.parse("2026-09-30T02:03:04.567Z")
      )), 15_000);
      backend.execute(model, new RecordWriteAdmission("tenant-a", 0), List.of(compiler.mutation(
          model,
          access,
          new RecordDeleteMutation("delete", created.recordId(), 1),
          Instant.parse("2026-09-30T03:04:05.678Z")
      )), 15_000);
      var timestampQuery = backend.query(
          model,
          new RecordSelectPlan(
              "SELECT \"id\" FROM \"runtime\".\"" + table
                  + "\" WHERE \"created_at\" = CAST(? AS TIMESTAMP(6))",
              List.of("2026-09-30T01:02:03.456"),
              List.of(),
              10
          ),
          15_000
      );
      assertThat(timestampQuery.rows()).hasSize(1);
      var creation = backend.query(model, compiler.creation(model, access, created.recordId()), 15_000);
      assertThat(creation.rows().getFirst()).containsExactly(created.recordId());
      try (Connection connection = dataSource.getConnection();
           var prepared = connection.prepareStatement(
               "SELECT record_version, deleted_at, title, created_at, updated_at FROM "
                   + table + " WHERE id = ?"
           )) {
        prepared.setString(1, created.recordId());
        try (ResultSet result = prepared.executeQuery()) {
          assertThat(result.next()).isTrue();
          assertThat(result.getLong(1)).isEqualTo(2L);
          assertThat(result.getObject(2)).isNotNull();
          assertThat(result.getString(3)).isEqualTo("service-update");
          // MySQL DATETIME has no zone; the runtime stores UTC civil values.
          Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
          assertThat(result.getTimestamp(4, utc).toInstant())
              .isEqualTo(Instant.parse("2026-09-30T01:02:03.456Z"));
          assertThat(result.getTimestamp(5, utc).toInstant())
              .isEqualTo(Instant.parse("2026-09-30T03:04:05.678Z"));
        }
      }
    } finally {
      try (Connection connection = dataSource.getConnection();
           Statement statement = connection.createStatement()) {
        statement.execute("DROP TABLE " + table);
      }
    }
  }

  private static RuntimeRecordModel model(
      final String table,
      final String catalog,
      final String schema
  ) {
    return new RuntimeRecordModel(
        "model-1",
        "app-1",
        "form-1",
        1,
        new PhysicalSchemaDefinition(
            DnaRuntimeApiVersion.CURRENT,
            List.of(new LogicalFieldDefinition(
                "field-title",
                "title",
                "Title",
                LogicalFieldType.STRING,
                false,
                128,
                null,
                null,
                null
            )),
            List.of()
        ),
        new PhysicalSchemaTarget(
            "model-1",
            new RuntimeMetadataTarget("source-1", catalog, schema, table)
        )
    );
  }

  private static RecordAccessContext access() {
    return new RecordAccessContext(
        new RuntimeActor("tenant-a", "user-a"),
        "app.forms.test",
        Set.of("id", "record_version", "title"),
        Set.of("title"),
        Set.of(),
        new DataScopeCondition(
            "ALL",
            "created_org_dept_id",
            "created_by",
            "user-a",
            Set.of()
        ),
        "dept-a"
    );
  }

  private static RecordSqlStatement insert(final String table, final String id) {
    return new RecordSqlStatement(
        "INSERT INTO \"runtime\".\"" + table + "\" "
            + "(\"id\", \"tenant_id\", \"record_version\", \"title\") VALUES (?, ?, ?, ?)",
        List.of(id, "tenant-a", 0L, "title-" + id),
        RecordMutationType.CREATE,
        id,
        0,
        Instant.now()
    );
  }

  private static RecordSqlStatement updateMissing(final String table, final String recordId) {
    return new RecordSqlStatement(
        "UPDATE \"runtime\".\"" + table + "\" SET \"title\" = ? WHERE \"id\" = ?",
        List.of("changed", recordId),
        RecordMutationType.UPDATE,
        recordId,
        1,
        Instant.now()
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

  private record DriverManagerDataSource(String url, String username, String password)
      implements DataSource {

    @Override
    public Connection getConnection() throws SQLException {
      return DriverManager.getConnection(url, username, password);
    }

    @Override
    public Connection getConnection(final String user, final String pass) throws SQLException {
      return DriverManager.getConnection(url, user, pass);
    }

    @Override
    public PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(final PrintWriter out) {
    }

    @Override
    public void setLoginTimeout(final int seconds) {
    }

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(final Class<T> iface) throws SQLException {
      throw new SQLException("Not a wrapper");
    }

    @Override
    public boolean isWrapperFor(final Class<?> iface) {
      return false;
    }
  }
}
