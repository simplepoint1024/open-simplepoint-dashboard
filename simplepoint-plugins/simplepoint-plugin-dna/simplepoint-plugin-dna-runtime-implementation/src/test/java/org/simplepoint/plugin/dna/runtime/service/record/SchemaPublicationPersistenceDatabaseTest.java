package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationStatus;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelVersionCommand;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalTableSnapshot;
import org.simplepoint.plugin.dna.runtime.api.service.RuntimeMetadataCacheInvalidator;
import org.simplepoint.plugin.dna.runtime.configuration.DnaRuntimePublicationProperties;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.entity.SchemaMigrationEntity;
import org.simplepoint.plugin.dna.runtime.service.impl.SchemaPublicationServiceImpl;
import org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchDatabaseTest.Database;
import org.simplepoint.plugin.dna.runtime.service.schema.InitialSchemaPlan;
import org.simplepoint.plugin.dna.runtime.service.schema.JdbcPhysicalSchemaBackend;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaSnapshotCodec;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.schema.PlannedSchemaStep;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordJournalProvisioner;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordWriteFenceProvisioner;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;

/**
 * Real control transactions, target DDL and process-local recreation across two different failure
 * boundaries.
 */
class SchemaPublicationPersistenceDatabaseTest {
  @Test
  void postgresqlResumesCommittedDdlAndThenCacheFailureFromDurablePlan() throws Exception {
    RecordPublicationPatchDatabaseTest.verify("POSTGRES", true, true, this::verify);
  }

  @Test
  void mysqlResumesCommittedDdlAndThenCacheFailureFromDurablePlan() throws Exception {
    RecordPublicationPatchDatabaseTest.verify("MYSQL", false, true, this::verify);
  }

  private void verify(Database db) throws Exception {
    try {
      RecordMaintenanceControlDatabaseTest.withControl(
          db,
          control -> {
            RecordMaintenanceControlDatabaseTest.authorize();
            var model = db.fixture().parent;
            String modelId = model.physicalModelId();
            control
                .transactions()
                .executeWithoutResult(
                    status ->
                        control
                            .em()
                            .find(PhysicalModelEntity.class, modelId)
                            .setDisplayName("Publication patch business fixture"));
            var fields = new ArrayList<>(model.activeSchema().fields());
            fields.add(
                new LogicalFieldDefinition(
                    "recovery",
                    "recovery_value",
                    "恢复验证",
                    LogicalFieldType.STRING,
                    true,
                    80,
                    null,
                    null,
                    null));
            var desired =
                new PhysicalSchemaDefinition(
                    model.activeSchema().contractVersion(),
                    fields,
                    model.activeSchema().indexes(),
                    model.activeSchema().owner(),
                    true);
            long version = version(control, modelId);
            var targetVersion =
                control
                    .physical()
                    .createVersion(modelId, new PhysicalModelVersionCommand(version, desired));
            var provider = provider(db);
            var codec = new PhysicalSchemaSnapshotCodec(new ObjectMapper());
            var first = new ObservedBackend(provider, codec, true);
            var cache = mock(RuntimeMetadataCacheInvalidator.class);
            var firstService = service(db, control, first, cache);
            assertThatThrownBy(
                    () ->
                        firstService.publish(
                            modelId,
                            new SchemaPublicationCommand(
                                targetVersion.version(), version(control, modelId))))
                .isInstanceOfSatisfying(
                    DnaRuntimeException.class,
                    failure ->
                        assertThat(failure.errorCode())
                            .isEqualTo(DnaRuntimeErrorCode.SCHEMA_PUBLICATION_INTERRUPTED));
            assertThat(first.executed).hasSize(1);
            var failed = firstService.getByTargetVersion(modelId, targetVersion.version());
            assertThat(failed.status()).isEqualTo(SchemaMigrationStatus.FAILED);
            String savedPlan = plan(control, failed.migrationId());
            assertThat(savedPlan).isNotBlank();

            // A new orchestration/backend instance must load the original plan from committed
            // control rows.
            var second = new ObservedBackend(provider, codec, false);
            doThrow(new IllegalStateException("simulated cache outage"))
                .when(cache)
                .invalidate(any());
            var secondService = service(db, control, second, cache);
            assertThatThrownBy(
                    () -> secondService.retry(failed.migrationId(), version(control, modelId)))
                .isInstanceOfSatisfying(
                    DnaRuntimeException.class,
                    failure ->
                        assertThat(failure.errorCode())
                            .isEqualTo(DnaRuntimeErrorCode.METADATA_INVALIDATION_FAILED));
            assertThat(second.executed).isNotEmpty().doesNotContain(first.executed.getFirst());
            assertThat(plan(control, failed.migrationId())).isEqualTo(savedPlan);
            assertThat(secondService.get(failed.migrationId()).status())
                .isEqualTo(SchemaMigrationStatus.FAILED);

            var third = new ObservedBackend(provider, codec, false);
            var finalService =
                service(db, control, third, mock(RuntimeMetadataCacheInvalidator.class));
            var active = finalService.retry(failed.migrationId(), version(control, modelId));
            assertThat(active.status()).isEqualTo(SchemaMigrationStatus.ACTIVE);
            assertThat(third.executed).isEmpty();
            assertThat(plan(control, failed.migrationId())).isEqualTo(savedPlan);
            Integer activeVersion =
                control
                    .transactions()
                    .execute(
                        status ->
                            control
                                .em()
                                .find(PhysicalModelEntity.class, modelId)
                                .getActiveVersion());
            assertThat(activeVersion).isEqualTo(targetVersion.version());
            assertThat(third.inspect(model.target(), db.dialect().dialectId()).columns())
                .anyMatch(column -> column.name().equals("recovery_value"));
          });
    } finally {
      RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    }
  }

  static ManagedRuntimeDataSourceProvider provider(Database db) throws Exception {
    var provider = mock(ManagedRuntimeDataSourceProvider.class);
    try (var connection = db.source().getConnection()) {
      var metadata = connection.getMetaData();
      var identity =
          new DatabaseIdentity(
              metadata.getDatabaseProductName(),
              metadata.getDatabaseProductVersion(),
              metadata.getDatabaseMajorVersion(),
              metadata.getDatabaseMinorVersion());
      when(provider.open(any()))
          .thenReturn(
              new ManagedRuntimeDataSource(
                  new SimpleDataSource(db.source()),
                  false,
                  db.context(),
                  db.core(),
                  db.dialect(),
                  identity,
                  db.catalog(),
                  db.schema()));
    }
    return provider;
  }

  static SchemaPublicationServiceImpl service(
      Database db,
      RecordMaintenanceControlDatabaseTest.ControlFixture control,
      JdbcPhysicalSchemaBackend backend,
      RuntimeMetadataCacheInvalidator cache) {
    return new SchemaPublicationServiceImpl(
        new RuntimeRequestContext(),
        control.publication(),
        backend,
        cache,
        new DnaRuntimePublicationProperties(),
        new RecordJournalProvisioner(backend),
        new RecordWriteFencePreparation(new RecordWriteFenceProvisioner(backend), db.backend()));
  }

  static long version(RecordMaintenanceControlDatabaseTest.ControlFixture control, String model) {
    return control
        .transactions()
        .execute(status -> control.em().find(PhysicalModelEntity.class, model).getLockVersion());
  }

  static String plan(
      RecordMaintenanceControlDatabaseTest.ControlFixture control, String migration) {
    return control
        .transactions()
        .execute(status -> control.em().find(SchemaMigrationEntity.class, migration).getPlanJson());
  }

  static final class ObservedBackend extends JdbcPhysicalSchemaBackend {
    private final AtomicBoolean interrupt;
    final List<String> executed = new ArrayList<>();

    ObservedBackend(
        ManagedRuntimeDataSourceProvider provider,
        PhysicalSchemaSnapshotCodec codec,
        boolean interrupt) {
      super(provider, codec);
      this.interrupt = new AtomicBoolean(interrupt);
    }

    @Override
    public <T> T withExclusivePublication(
        PhysicalSchemaTarget target, Function<PublicationSession, T> callback) {
      return super.withExclusivePublication(
          target,
          session ->
              callback.apply(
                  new PublicationSession() {
                    @Override
                    public void prepareInfrastructure() {
                      session.prepareInfrastructure();
                    }

                    @Override
                    public InitialSchemaPlan plan(
                        String comment,
                        PhysicalSchemaDefinition current,
                        PhysicalSchemaDefinition desired) {
                      return session.plan(comment, current, desired);
                    }

                    @Override
                    public PhysicalTableSnapshot inspect(String dialect) {
                      return session.inspect(dialect);
                    }

                    @Override
                    public void verifyReferences(
                        PhysicalSchemaDefinition schema, PhysicalTableSnapshot actual) {
                      session.verifyReferences(schema, actual);
                    }

                    @Override
                    public void execute(String dialect, PlannedSchemaStep step) {
                      session.execute(dialect, step);
                      executed.add(step.statementChecksum());
                      if (interrupt.compareAndSet(true, false)) {
                        throw new DnaRuntimeException(
                            DnaRuntimeErrorCode.SCHEMA_PUBLICATION_INTERRUPTED,
                            "Simulated lost DDL reply");
                      }
                    }
                  }));
    }
  }
}
