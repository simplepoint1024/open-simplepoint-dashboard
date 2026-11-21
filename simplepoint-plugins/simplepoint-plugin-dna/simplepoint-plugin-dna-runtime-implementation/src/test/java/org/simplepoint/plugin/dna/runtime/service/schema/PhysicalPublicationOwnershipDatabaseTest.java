package org.simplepoint.plugin.dna.runtime.service.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.data.ownership.PhysicalObjectOwner;
import org.simplepoint.data.ownership.PhysicalOwnershipSession;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Native publication must use the namespace identity and lock that protected registration. */
class PhysicalPublicationOwnershipDatabaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void publishesUnderTheSameNamespaceLockAsRegistration(boolean postgres) throws Exception {
    try (var fixture = new PhysicalOwnershipDatabaseFixture(postgres)) {
      var target = installed(fixture);
      var backend = backend(fixture.provider());
      backend.withExclusivePublication(
          target,
          session -> {
            reject(
                () ->
                    fixture
                        .registry()
                        .reserve("tenant", "another", fixture.placement("alias"), "other"),
                DnaRuntimeErrorCode.PHYSICAL_MODEL_REGISTRATION_CONFLICT);
            var plan = session.plan("客户", null, schema());
            for (var step : plan.steps()) {
              session.execute(plan.dialectId(), step);
            }
            assertThat(session.inspect(plan.dialectId()).checksum())
                .isEqualTo(plan.targetSnapshot().checksum());
            return null;
          });
      assertThat(backend.inspect(target, postgres ? "postgresql" : "mysql").exists()).isTrue();
      assertThatThrownBy(
              () ->
                  backend.withExclusivePublication(
                      target,
                      session -> {
                        throw new IllegalStateException("Injected callback failure");
                      }))
          .isInstanceOf(IllegalStateException.class);
      backend.withExclusivePublication(target, session -> null);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void rejectsMissingReplacedOrForeignOwnershipBeforeInvokingPublication(boolean postgres)
      throws Exception {
    try (var fixture = new PhysicalOwnershipDatabaseFixture(postgres)) {
      var target = installed(fixture);
      var backend = backend(fixture.provider());
      var callback = new java.util.concurrent.atomic.AtomicBoolean();
      for (var changed :
          List.of(
              new PhysicalSchemaTarget("model", target.metadataTarget()),
              target.withOwnership(UUID.randomUUID().toString(), "tenant"),
              target.withOwnership(target.ownership().namespaceId(), "other-tenant"),
              new PhysicalSchemaTarget(
                  "other-model", target.metadataTarget(), Map.of(), target.ownership()))) {
        reject(
            () ->
                backend.withExclusivePublication(
                    changed,
                    session -> {
                      callback.set(true);
                      return null;
                    }),
            changed.ownership() == null
                ? DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_UNAVAILABLE
                : DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_CONFLICT);
      }
      assertThat(callback).isFalse();
      assertThat(backend.inspect(target, postgres ? "postgresql" : "mysql").exists()).isFalse();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void referenceTargetsRequireTheirOwnMatchingRegistration(boolean postgres) throws Exception {
    try (var fixture = new PhysicalOwnershipDatabaseFixture(postgres)) {
      var target = installed(fixture);
      var backend = backend(fixture.provider());
      var metadata = target.metadataTarget();
      for (String table : List.of("missing", "wrong", "related")) {
        if (!table.equals("missing")) {
          try (var connection = fixture.source.getConnection();
              var owner = PhysicalOwnershipSession.open(connection, fixture.namespace)) {
            owner.claimNew(
                table,
                true,
                new PhysicalObjectOwner(
                    "dna-runtime",
                    "tenant",
                    table.equals("wrong") ? "other-model" : "related-model"));
          }
        }
        var dependency =
            new RuntimeMetadataTarget(
                metadata.dataSourceId(), metadata.catalogName(), metadata.schemaName(), table);
        var related =
            new PhysicalSchemaTarget(
                target.physicalModelId(),
                metadata,
                Map.of("related-model", dependency),
                target.ownership());
        if (table.equals("related")) {
          backend.withExclusivePublication(related, session -> null);
        } else {
          reject(
              () -> backend.withExclusivePublication(related, session -> null),
              DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_CONFLICT);
        }
      }
      var crossNamespace =
          new RuntimeMetadataTarget(
              metadata.dataSourceId(), metadata.catalogName(), "another-schema", "related");
      var changed =
          new PhysicalSchemaTarget(
              target.physicalModelId(),
              metadata,
              Map.of("related-model", crossNamespace),
              target.ownership());
      reject(
          () -> backend.withExclusivePublication(changed, session -> null),
          DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_CONFLICT);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void lostExecutingSessionLockPreventsTheNextDdlStatement(boolean postgres) throws Exception {
    try (var fixture = new PhysicalOwnershipDatabaseFixture(postgres)) {
      var target = installed(fixture);
      var provider = fixture.provider();
      var executing = new AtomicReference<Connection>();
      replaceConnection(
          provider,
          target,
          () -> {
            var connection = fixture.source.getConnection();
            executing.set(connection);
            return connection;
          });
      var backend = backend(provider);
      var discarded = new AtomicReference<Connection>();
      reject(
          () ->
              backend.withExclusivePublication(
                  target,
                  session -> {
                    var plan = session.plan("客户", null, schema());
                    try {
                      discarded.set(executing.get());
                      fixture.execute(
                          executing.get(),
                          postgres
                              ? "SELECT pg_catalog.pg_advisory_unlock_all()"
                              : "SELECT RELEASE_ALL_LOCKS()");
                    } catch (SQLException failure) {
                      throw new IllegalStateException(failure);
                    }
                    session.execute(plan.dialectId(), plan.steps().getFirst());
                    return null;
                  }),
          DnaRuntimeErrorCode.SCHEMA_PUBLICATION_FAILED);
      assertThat(discarded.get().isClosed()).isTrue();
      assertThat(backend.inspect(target, postgres ? "postgresql" : "mysql").exists()).isFalse();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void publicationNeverCommitsAnUnexpectedBorrowedTransaction(boolean postgres) throws Exception {
    try (var fixture = new PhysicalOwnershipDatabaseFixture(postgres)) {
      var target = installed(fixture);
      try (var connection = fixture.source.getConnection()) {
        fixture.execute(
            connection, "CREATE TABLE " + fixture.table("records") + " (id INTEGER PRIMARY KEY)");
      }
      var provider = fixture.provider();
      replaceConnection(
          provider,
          target,
          () -> {
            var connection = fixture.source.getConnection();
            connection.setAutoCommit(false);
            fixture.execute(connection, "INSERT INTO " + fixture.table("records") + " VALUES (1)");
            return connection;
          });
      reject(
          () -> backend(provider).withExclusivePublication(target, session -> null),
          DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_UNAVAILABLE);
      try (var connection = fixture.source.getConnection();
          var statement = connection.createStatement();
          var rows = statement.executeQuery("SELECT COUNT(*) FROM " + fixture.table("records"))) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isZero();
      }
    }
  }

  private static PhysicalSchemaTarget installed(PhysicalOwnershipDatabaseFixture fixture)
      throws SQLException {
    UUID namespace = UUID.randomUUID();
    try (var connection = fixture.source.getConnection()) {
      RuntimeOwnershipTestFixture.installFresh(connection, fixture.namespace, namespace);
      try (var session = PhysicalOwnershipSession.open(connection, fixture.namespace)) {
        session.claimNew(
            "records", true, new PhysicalObjectOwner("dna-runtime", "tenant", "model"));
      }
    }
    var placement = fixture.placement("source");
    return new PhysicalSchemaTarget(
            "model",
            new RuntimeMetadataTarget(
                placement.dataSourceId(),
                placement.catalogName(),
                placement.schemaName(),
                "records"))
        .withOwnership(namespace.toString(), "tenant");
  }

  private static JdbcPhysicalSchemaBackend backend(ManagedRuntimeDataSourceProvider provider) {
    return new JdbcPhysicalSchemaBackend(
        provider, new PhysicalSchemaSnapshotCodec(new ObjectMapper()));
  }

  private static PhysicalSchemaDefinition schema() {
    return new PhysicalSchemaDefinition(
        DnaRuntimeApiVersion.CURRENT,
        List.of(
            new LogicalFieldDefinition(
                "title", "title", "客户名称", LogicalFieldType.STRING, true, 100, null, null, null)),
        List.of());
  }

  private static void replaceConnection(
      ManagedRuntimeDataSourceProvider provider,
      PhysicalSchemaTarget target,
      ConnectionFactory connections) {
    var handle = provider.open(target);
    var source =
        new AbstractDataSource() {
          @Override
          public Connection getConnection() throws SQLException {
            return connections.open();
          }

          @Override
          public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("Unexpected credential override");
          }
        };
    when(provider.open(any()))
        .thenReturn(
            new ManagedRuntimeDataSource(
                new SimpleDataSource(source),
                false,
                handle.supportContext(),
                handle.coreDialect(),
                handle.runtimeDialect(),
                handle.database(),
                handle.metadataCatalog(),
                handle.metadataSchema()));
  }

  private static void reject(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable operation, DnaRuntimeErrorCode code) {
    assertThatThrownBy(operation)
        .isInstanceOfSatisfying(
            DnaRuntimeException.class, failure -> assertThat(failure.errorCode()).isEqualTo(code));
  }

  @FunctionalInterface
  private interface ConnectionFactory {
    Connection open() throws SQLException;
  }
}
