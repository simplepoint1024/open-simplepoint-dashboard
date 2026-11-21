package org.simplepoint.plugin.dna.runtime.service.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.MysqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.PostgresqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationOperation;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationRisk;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Exercises the actual DNA planner, executor, drift checks and retry predicate on both databases.
 */
class TenantRecordIdentityDatabaseTest {
  @Test
  void postgresqlAddsTenantIdentityWithoutRewritingExistingRecords() throws Exception {
    verify(true);
  }

  @Test
  void mysqlAddsTenantIdentityWithoutRewritingExistingRecords() throws Exception {
    verify(false);
  }

  private void verify(boolean postgres) throws Exception {
    String prefix = "DNA_RUNTIME_TEST_" + (postgres ? "POSTGRES" : "MYSQL");
    String url = System.getenv(prefix + "_URL");
    Assumptions.assumeTrue(url != null && !url.isBlank(), "Disposable database is required");
    var source =
        new DriverManagerDataSource(
            url, System.getenv(prefix + "_USER"), System.getenv(prefix + "_PASSWORD"));
    String namespace = "identity_ns_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = source.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("CREATE " + (postgres ? "SCHEMA " : "DATABASE ") + namespace);
    }
    try {
      verifyNamespace(source, postgres, namespace);
    } finally {
      try (var connection = source.getConnection();
          var statement = connection.createStatement()) {
        statement.execute(
            "DROP "
                + (postgres ? "SCHEMA " : "DATABASE ")
                + namespace
                + (postgres ? " CASCADE" : ""));
      }
    }
  }

  private void verifyNamespace(DriverManagerDataSource source, boolean postgres, String namespace)
      throws Exception {
    var codec = new PhysicalSchemaSnapshotCodec(new ObjectMapper());
    JdbcDatabaseDialect core =
        postgres ? new PostgresqlJdbcDatabaseDialect() : new MysqlJdbcDatabaseDialect();
    AbstractRuntimeSchemaDialect dialect =
        postgres ? new PostgresqlRuntimeSchemaDialect(codec) : new MysqlRuntimeSchemaDialect(codec);
    String table = "sp_identity_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = source.getConnection();
        var statement = connection.createStatement()) {
      if (postgres) {
        connection.setSchema(namespace);
      } else {
        connection.setCatalog(namespace);
      }
      UUID namespaceId = UUID.randomUUID();
      org.simplepoint.plugin.dna.runtime.service.schema.RuntimeOwnershipTestFixture.installFresh(
          connection, namespace, namespaceId);
      try (var session =
          org.simplepoint.data.ownership.PhysicalOwnershipSession.open(connection, namespace)) {
        session.claimNew(
            table,
            true,
            new org.simplepoint.data.ownership.PhysicalObjectOwner(
                "dna-runtime", "tenant-a", "model"));
      }
      var metadata = connection.getMetaData();
      var context =
          new JdbcDatabaseDialect.SupportContext(
              core.code(),
              metadata.getDriverName(),
              metadata.getDatabaseProductName(),
              metadata.getDatabaseProductVersion(),
              connection.getCatalog(),
              connection.getSchema(),
              metadata.getIdentifierQuoteString(),
              Map.of());
      var database =
          new DatabaseIdentity(
              metadata.getDatabaseProductName(),
              metadata.getDatabaseProductVersion(),
              metadata.getDatabaseMajorVersion(),
              metadata.getDatabaseMinorVersion());
      var target =
          new PhysicalSchemaTarget(
                  "model",
                  new RuntimeMetadataTarget(
                      "test",
                      connection.getCatalog(),
                      postgres ? connection.getSchema() : null,
                      table))
              .withOwnership(namespaceId.toString(), "tenant-a");
      String physicalTable = dialect.qualifiedTable(core, context, target);
      var provider = mock(ManagedRuntimeDataSourceProvider.class);
      when(provider.open(any()))
          .thenReturn(
              new ManagedRuntimeDataSource(
                  new SimpleDataSource(source),
                  false,
                  context,
                  core,
                  dialect,
                  database,
                  connection.getCatalog(),
                  postgres ? connection.getSchema() : null));
      var backend = new JdbcPhysicalSchemaBackend(provider, codec);
      String lostSessionKey = codec.statementChecksum("test-session", table);
      try (var lost = source.getConnection()) {
        SchemaPublicationSessionLock.acquire(lost, dialect.dialectId(), lostSessionKey);
        lost.abort(Runnable::run);
      }
      try (var replacement = source.getConnection();
          var recoveredLock =
              SchemaPublicationSessionLock.acquire(
                  replacement, dialect.dialectId(), lostSessionKey)) {
        assertThat(replacement.isClosed()).isFalse();
      }
      var original =
          new PhysicalSchemaDefinition(
              DnaRuntimeApiVersion.CURRENT,
              List.of(
                  new LogicalFieldDefinition(
                      "title",
                      "title",
                      "标题",
                      LogicalFieldType.STRING,
                      true,
                      120,
                      null,
                      null,
                      null)),
              List.of());
      var enabled =
          new PhysicalSchemaDefinition(
              original.contractVersion(), original.fields(), original.indexes(), null, true);
      try {
        backend.withExclusivePublication(
            target,
            session -> {
              assertThatThrownBy(() -> backend.withExclusivePublication(target, another -> null))
                  .isInstanceOfSatisfying(
                      DnaRuntimeException.class,
                      failure ->
                          assertThat(failure.errorCode())
                              .isEqualTo(DnaRuntimeErrorCode.SCHEMA_MIGRATION_CONFLICT));
              var initial = session.plan("客户", null, original);
              for (var step : initial.steps()) {
                session.execute(initial.dialectId(), step);
                assertThat(session.inspect(initial.dialectId()).checksum())
                    .isEqualTo(initial.checkpointChecksums().get(step.sequence()));
              }
              return null;
            });
        assertThatThrownBy(
                () ->
                    backend.withExclusivePublication(
                        target,
                        session -> {
                          throw new IllegalStateException("simulated process interruption");
                        }))
            .isInstanceOf(IllegalStateException.class);
        // Both successful and exceptional exits must release the physical-session lock.
        backend.withExclusivePublication(
            target,
            session -> {
              assertThat(session.inspect(dialect.dialectId()).exists()).isTrue();
              return null;
            });
        statement.executeUpdate(
            "INSERT INTO "
                + physicalTable
                + " (id,tenant_id,created_by,created_at,updated_by,updated_at,title) VALUES "
                + "('00000000-0000-0000-0000-000000000001','tenant-a','actor',CURRENT_TIMESTAMP,'actor',CURRENT_TIMESTAMP,'客户·顧客')");
        var evolution = backend.plan(target, "客户", original, enabled);
        assertThat(evolution.risk()).isEqualTo(SchemaMigrationRisk.SAFE);
        assertThat(evolution.steps())
            .singleElement()
            .satisfies(
                step ->
                    assertThat(step.operation()).isEqualTo(SchemaMigrationOperation.CREATE_INDEX));
        var key =
            evolution.targetSnapshot().indexes().stream()
                .filter(index -> index.columns().equals(List.of("tenant_id", "id")))
                .findFirst()
                .orElseThrow();
        assertThat(key.unique()).isTrue();
        assertThat(key.primary()).isFalse();
        executeMissing(backend, target, evolution);
        // A lost DDL acknowledgement must be recoverable from the actual key, without creating it
        // again.
        assertThat(
                backend.isSatisfied(
                    evolution.steps().getFirst(),
                    evolution.targetSnapshot(),
                    backend.inspect(target, dialect.dialectId())))
            .isTrue();
        executeMissing(backend, target, evolution);
        executeMissing(backend, target, backend.planInitial(target, "客户", enabled));
        try (var rows =
            statement.executeQuery("SELECT tenant_id,record_version,title FROM " + physicalTable)) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString(1)).isEqualTo("tenant-a");
          assertThat(rows.getLong(2)).isZero();
          assertThat(rows.getString(3)).isEqualTo("客户·顧客");
          assertThat(rows.next()).isFalse();
        }
        assertThatThrownBy(() -> backend.plan(target, "客户", enabled, original))
            .isInstanceOfSatisfying(
                DnaRuntimeException.class,
                failure ->
                    assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.SCHEMA_INVALID));
        statement.execute("DROP TABLE " + physicalTable);
        executeMissing(backend, target, backend.planInitial(target, "客户", enabled));
        assertThat(backend.inspect(target, dialect.dialectId()).indexes()).contains(key);
      } finally {
        statement.execute("DROP TABLE IF EXISTS " + physicalTable);
      }
    }
  }

  private static void executeMissing(
      JdbcPhysicalSchemaBackend backend, PhysicalSchemaTarget target, InitialSchemaPlan plan) {
    for (var step : plan.steps()) {
      if (!backend.isSatisfied(
          step, plan.targetSnapshot(), backend.inspect(target, plan.dialectId()))) {
        backend.execute(target, plan.dialectId(), step);
      }
    }
    assertThat(backend.inspect(target, plan.dialectId()).checksum())
        .isEqualTo(plan.targetSnapshot().checksum());
  }
}
