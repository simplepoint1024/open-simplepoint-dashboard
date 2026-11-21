package org.simplepoint.plugin.dna.runtime.service.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.data.ownership.PhysicalObjectOwner;
import org.simplepoint.data.ownership.PhysicalOwnershipSession;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;

/** Real shared infrastructure must participate in the same ownership protocol as business DDL. */
class RuntimeInfrastructureOwnershipDatabaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void removesOwnedParentAndDetailAndOnlyTheirRowsInSharedStorage(boolean postgres) throws Exception {
    try (var fixture = new PhysicalOwnershipDatabaseFixture(postgres)) {
      var parent = register(fixture, "source", "tenant-a", "retiring_parent");
      var detail = register(fixture, "source", "tenant-a", "retiring_detail");
      var retained = register(fixture, "source", "tenant-b", "retained_records");
      var provider = fixture.provider();
      var backend = new JdbcPhysicalSchemaBackend(provider, new PhysicalSchemaSnapshotCodec(new ObjectMapper()));
      provision(backend, parent);
      String parentTable = fixture.table(parent.metadataTarget().tableName());
      String detailTable = fixture.table(detail.metadataTarget().tableName());
      String retainedTable = fixture.table(retained.metadataTarget().tableName());
      String journal = fixture.table(RuntimeInfrastructureTable.OPERATIONS.table());
      try (var connection = fixture.source.getConnection()) {
        fixture.execute(connection, "CREATE TABLE " + parentTable + " (id INT PRIMARY KEY)");
        fixture.execute(connection, "CREATE TABLE " + detailTable
            + " (id INT PRIMARY KEY, parent_id INT, FOREIGN KEY (parent_id) REFERENCES " + parentTable + "(id))");
        fixture.execute(connection, "CREATE TABLE " + retainedTable + " (id INT PRIMARY KEY)");
        fixture.execute(connection, "INSERT INTO " + parentTable + " VALUES (1)");
        fixture.execute(connection, "INSERT INTO " + detailTable + " VALUES (1, 1)");
        fixture.execute(connection, "INSERT INTO " + retainedTable + " VALUES (2)");
        for (var value : List.of(List.of("tenant-a", parent.physicalModelId()),
            List.of("tenant-a", detail.physicalModelId()), List.of("tenant-b", retained.physicalModelId()))) {
          fixture.execute(connection, "INSERT INTO " + journal
              + " (id, tenant_id, record_version, created_by, created_at, updated_by, updated_at,"
              + " model_id, operation_key, request_fingerprint, result_json) VALUES ('" + UUID.randomUUID()
              + "', '" + value.get(0) + "', 1, 'seed', CURRENT_TIMESTAMP, 'seed', CURRENT_TIMESTAMP, '"
              + value.get(1) + "', 'seed', 'fingerprint', '{}')");
        }
      }
      var target = parent.metadataTarget();
      var plan = new FormPhysicalStorageRemoval.Plan(target.dataSourceId(), target.catalogName(), target.schemaName(),
          UUID.fromString(parent.ownership().namespaceId()), List.of(
              new FormPhysicalStorageRemoval.Table(detail.physicalModelId(), detail.metadataTarget().tableName()),
              new FormPhysicalStorageRemoval.Table(parent.physicalModelId(), target.tableName())));
      var removal = new FormPhysicalStorageRemoval(provider);
      removal.execute("tenant-a", plan);
      removal.execute("tenant-a", plan);
      try (var connection = fixture.source.getConnection(); var statement = connection.createStatement()) {
        assertThatThrownBy(() -> fixture.execute(connection, "SELECT * FROM " + parentTable)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> fixture.execute(connection, "SELECT * FROM " + detailTable)).isInstanceOf(SQLException.class);
        fixture.execute(connection, "SELECT * FROM " + retainedTable);
        try (var rows = statement.executeQuery("SELECT tenant_id FROM " + journal)) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString(1)).isEqualTo("tenant-b");
          assertThat(rows.next()).isFalse();
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void publicationPreparesInfrastructureWithoutOpeningAnotherDdlConnection(boolean postgres)
      throws Exception {
    try (var fixture = new PhysicalOwnershipDatabaseFixture(postgres)) {
      var model = register(fixture, "source", "tenant", "model");
      var provider = fixture.provider();
      var backend =
          new JdbcPhysicalSchemaBackend(
              provider,
              new PhysicalSchemaSnapshotCodec(new ObjectMapper().findAndRegisterModules()));
      backend.withExclusivePublication(
          model,
          session -> {
            session.prepareInfrastructure();
            session.prepareInfrastructure();
            return null;
          });
      verify(provider, times(1)).open(any());
      try (var connection = fixture.source.getConnection();
          var ownership = PhysicalOwnershipSession.open(connection, fixture.namespace)) {
        for (var table : RuntimeInfrastructureTable.values()) {
          assertThat(ownership.owner(table.table(), true)).contains(table.owner());
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void sharedTablesHaveStableOwnersAcrossModelsTenantsAndSourceAliases(boolean postgres)
      throws Exception {
    try (var fixture = new PhysicalOwnershipDatabaseFixture(postgres)) {
      var first = register(fixture, "source", "tenant-a", "first");
      var second = register(fixture, "alias", "tenant-b", "second");
      var backend = backend(fixture);
      provision(backend, first);
      provision(backend, second);
      try (var connection = fixture.source.getConnection();
          var ownership = PhysicalOwnershipSession.open(connection, fixture.namespace)) {
        for (var table : RuntimeInfrastructureTable.values()) {
          assertThat(ownership.owner(table.table(), true)).contains(table.owner());
          assertThat(table.owner().tenantId()).isNull();
          assertThat(
                  backend.inspect(table.target(first), postgres ? "postgresql" : "mysql").exists())
              .isTrue();
        }
      }
      reject(
          () ->
              fixture
                  .registry()
                  .reserve(
                      "tenant-a",
                      "takeover",
                      fixture.placement("alias"),
                      RecordJournalSchema.TABLE),
          DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_CONFLICT);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void existingUnownedOrForeignTablesCannotBeAdoptedByProvisioning(boolean postgres)
      throws Exception {
    try (var fixture = new PhysicalOwnershipDatabaseFixture(postgres)) {
      var model = register(fixture, "source", "tenant", "model");
      var backend = backend(fixture);
      var table = RuntimeInfrastructureTable.OPERATIONS;
      // Even a byte-for-byte matching legacy schema requires explicit adoption, not CREATE retry.
      var plan = backend.planInitial(table.target(model), table.description(), table.definition());
      for (var step : plan.steps()) {
        backend.execute(table.target(model), plan.dialectId(), step);
      }
      reject(
          () -> new RecordJournalProvisioner(backend).prepare(model),
          DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_CONFLICT);
      try (var connection = fixture.source.getConnection();
          var ownership = PhysicalOwnershipSession.open(connection, fixture.namespace)) {
        assertThat(ownership.owner(table.table(), true)).isEmpty();
        ownership.claimNew(
            RecordCalculationSchema.TABLE,
            true,
            new PhysicalObjectOwner("foreign-module", null, "retained"));
      }
      reject(
          () -> new RecordCalculationProvisioner(backend).prepare(model),
          DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_CONFLICT);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void rejectsMissingOrWrongModelIdentityBeforeReservingSharedTables(boolean postgres)
      throws Exception {
    try (var fixture = new PhysicalOwnershipDatabaseFixture(postgres)) {
      var model = register(fixture, "source", "tenant", "model");
      var provisioner = new RecordJournalProvisioner(backend(fixture));
      reject(
          () -> provisioner.prepare(new PhysicalSchemaTarget("model", model.metadataTarget())),
          DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_UNAVAILABLE);
      reject(
          () -> provisioner.prepare(model.withOwnership(UUID.randomUUID().toString(), "tenant")),
          DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_CONFLICT);
      reject(
          () -> provisioner.prepare(model.withOwnership(model.ownership().namespaceId(), "other")),
          DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_CONFLICT);
      try (var connection = fixture.source.getConnection();
          var ownership = PhysicalOwnershipSession.open(connection, fixture.namespace)) {
        for (var table : RuntimeInfrastructureTable.values()) {
          assertThat(ownership.owner(table.table(), true)).isEmpty();
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void interruptedDdlRetainsItsClaimAndResumesFromInspectedStructure(boolean postgres)
      throws Exception {
    try (var fixture = new PhysicalOwnershipDatabaseFixture(postgres)) {
      var model = register(fixture, "source", "tenant", "model");
      var backend = backend(fixture);
      var table = RuntimeInfrastructureTable.OPERATIONS;
      assertThatThrownBy(
              () ->
                  backend.withExclusiveInfrastructure(
                      model,
                      table,
                      session -> {
                        var plan = session.plan(table.description(), null, table.definition());
                        session.execute(plan.dialectId(), plan.steps().getFirst());
                        throw new IllegalStateException("Injected interruption after first DDL");
                      }))
          .isInstanceOf(IllegalStateException.class);
      try (var connection = fixture.source.getConnection();
          var ownership = PhysicalOwnershipSession.open(connection, fixture.namespace)) {
        assertThat(ownership.owner(table.table(), true)).contains(table.owner());
      }
      // A new backend/connection completes the same versioned definition without adopting tables.
      new RecordJournalProvisioner(backend(fixture)).prepare(model);
      var expected =
          backend.planInitial(table.target(model), table.description(), table.definition());
      assertThat(backend.inspect(table.target(model), expected.dialectId()).checksum())
          .isEqualTo(expected.targetSnapshot().checksum());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void namespaceLockExcludesRegistrationAndOtherProvisioners(boolean postgres) throws Exception {
    try (var fixture = new PhysicalOwnershipDatabaseFixture(postgres)) {
      var model = register(fixture, "source", "tenant", "model");
      var backend = backend(fixture);
      var invoked = new AtomicBoolean();
      backend.withExclusiveInfrastructure(
          model,
          RuntimeInfrastructureTable.OPERATIONS,
          session -> {
            reject(
                () ->
                    backend.withExclusiveInfrastructure(
                        model,
                        RuntimeInfrastructureTable.CALCULATIONS,
                        other -> {
                          invoked.set(true);
                          return null;
                        }),
                DnaRuntimeErrorCode.SCHEMA_MIGRATION_CONFLICT);
            reject(
                () ->
                    fixture
                        .registry()
                        .reserve("tenant", "other", fixture.placement("alias"), "other"),
                DnaRuntimeErrorCode.PHYSICAL_MODEL_REGISTRATION_CONFLICT);
            return null;
          });
      assertThat(invoked).isFalse();
      provision(backend, model);
    }
  }

  private static void provision(JdbcPhysicalSchemaBackend backend, PhysicalSchemaTarget model) {
    new RecordJournalProvisioner(backend).prepare(model);
    new RecordWriteFenceProvisioner(backend).prepare(model);
  }

  private static PhysicalSchemaTarget register(
      PhysicalOwnershipDatabaseFixture fixture, String source, String tenant, String model)
      throws SQLException {
    var placement = fixture.placement(source);
    return RuntimeOwnershipTestFixture.reserveNewModel(
        fixture.source,
        new PhysicalSchemaTarget(
            model,
            new RuntimeMetadataTarget(
                source, placement.catalogName(), placement.schemaName(), model)),
        tenant);
  }

  private static JdbcPhysicalSchemaBackend backend(PhysicalOwnershipDatabaseFixture fixture)
      throws SQLException {
    return new JdbcPhysicalSchemaBackend(
        fixture.provider(),
        new PhysicalSchemaSnapshotCodec(new ObjectMapper().findAndRegisterModules()));
  }

  private static void reject(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action, DnaRuntimeErrorCode code) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(
            DnaRuntimeException.class, failure -> assertThat(failure.errorCode()).isEqualTo(code));
  }
}
