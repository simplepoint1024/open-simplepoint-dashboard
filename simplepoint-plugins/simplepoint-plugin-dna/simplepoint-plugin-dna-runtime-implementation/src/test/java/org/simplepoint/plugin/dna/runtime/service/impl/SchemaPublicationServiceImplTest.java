package org.simplepoint.plugin.dna.runtime.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationOperation;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationRisk;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationStatus;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationStepStatus;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPublicationView;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalTableSnapshot;
import org.simplepoint.plugin.dna.runtime.api.service.RuntimeMetadataCacheInvalidator;
import org.simplepoint.plugin.dna.runtime.configuration.DnaRuntimePublicationProperties;
import org.simplepoint.plugin.dna.runtime.service.impl.SchemaPublicationStateService.PersistedStep;
import org.simplepoint.plugin.dna.runtime.service.impl.SchemaPublicationStateService.PublicationClaim;
import org.simplepoint.plugin.dna.runtime.service.impl.SchemaPublicationStateService.PublicationWork;
import org.simplepoint.plugin.dna.runtime.service.record.RecordWriteFencePreparation;
import org.simplepoint.plugin.dna.runtime.service.schema.InitialSchemaPlan;
import org.simplepoint.plugin.dna.runtime.service.schema.JdbcPhysicalSchemaBackend;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.schema.PlannedSchemaStep;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordJournalProvisioner;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

class SchemaPublicationServiceImplTest {

  private static final RuntimeActor ACTOR = new RuntimeActor("tenant-a", "user-a");

  private RuntimeRequestContext requestContext;
  private SchemaPublicationStateService stateService;
  private JdbcPhysicalSchemaBackend backend;
  private RuntimeMetadataCacheInvalidator cacheInvalidator;
  private SchemaPublicationServiceImpl service;
  private RecordJournalProvisioner journal;
  private RecordWriteFencePreparation writeFences;

  @BeforeEach
  void setUp() {
    requestContext = mock(RuntimeRequestContext.class);
    stateService = mock(SchemaPublicationStateService.class);
    backend = mock(JdbcPhysicalSchemaBackend.class);
    cacheInvalidator = mock(RuntimeMetadataCacheInvalidator.class);
    when(requestContext.requireActor()).thenReturn(ACTOR);
    journal = mock(RecordJournalProvisioner.class);
    writeFences = mock(RecordWriteFencePreparation.class);
    service = new SchemaPublicationServiceImpl(
        requestContext,
        stateService,
        backend,
        cacheInvalidator,
        new DnaRuntimePublicationProperties(),
        journal,
        writeFences
    );
  }

  @Test
  void persistsEveryStepAndInvalidatesCachesBeforeActivation() {
    PublicationWork work = work();
    InitialSchemaPlan plan = plan();
    SchemaPublicationView active = activeView();
    when(stateService.claim(any(), anyString(), any())).thenReturn(
        new PublicationClaim(true, work)
    );
    when(backend.plan(any(), anyString(), isNull(), any())).thenReturn(plan);
    when(stateService.steps(ACTOR.tenantId(), work.migrationId())).thenReturn(List.of(
        new PersistedStep(
            "step-1",
            1,
            SchemaMigrationOperation.CREATE_TABLE.name(),
            plan.steps().getFirst().statementChecksum(),
            SchemaMigrationStepStatus.PENDING
        )
    ));
    when(backend.inspect(work.target(), plan.dialectId()))
        .thenReturn(plan.actualSnapshot(), plan.targetSnapshot());
    when(backend.isSatisfied(any(), any(), any())).thenReturn(false);
    when(stateService.beginStep(
        ACTOR,
        work.migrationId(),
        work.executionToken(),
        1
    )).thenReturn("step-1");
    when(stateService.activate(
        ACTOR,
        work.migrationId(),
        work.executionToken(),
        plan.targetSnapshot().checksum()
    )).thenReturn(active);

    service.publish("model-1", new SchemaPublicationCommand(1, 0));

    InOrder order = inOrder(stateService, backend, cacheInvalidator, journal, writeFences);
    order.verify(stateService).ready(ACTOR, work.migrationId(), work.executionToken(), plan);
    order.verify(stateService).start(ACTOR, work.migrationId(), work.executionToken());
    order.verify(journal).prepare(work.target());
    order.verify(writeFences).prepare(ACTOR, work.target());
    order.verify(stateService).beginStep(
        ACTOR,
        work.migrationId(),
        work.executionToken(),
        1
    );
    order.verify(backend).execute(work.target(), plan.dialectId(), plan.steps().getFirst());
    order.verify(stateService).finishStep(
        ACTOR,
        work.migrationId(),
        work.executionToken(),
        "step-1",
        SchemaMigrationStepStatus.SUCCEEDED
    );
    order.verify(stateService).verifying(ACTOR, work.migrationId(), work.executionToken());
    order.verify(cacheInvalidator).invalidate(work.target().metadataTarget());
    order.verify(stateService).activate(
        ACTOR,
        work.migrationId(),
        work.executionToken(),
        plan.targetSnapshot().checksum()
    );
  }

  @Test
  void recordsCacheInvalidationFailureWithoutActivatingTheVersion() {
    PublicationWork work = work();
    InitialSchemaPlan plan = plan();
    when(stateService.claim(any(), anyString(), any())).thenReturn(
        new PublicationClaim(true, work)
    );
    when(backend.plan(any(), anyString(), isNull(), any())).thenReturn(plan);
    when(stateService.steps(ACTOR.tenantId(), work.migrationId())).thenReturn(List.of(
        new PersistedStep(
            "step-1",
            1,
            SchemaMigrationOperation.CREATE_TABLE.name(),
            plan.steps().getFirst().statementChecksum(),
            SchemaMigrationStepStatus.PENDING
        )
    ));
    when(backend.inspect(work.target(), plan.dialectId()))
        .thenReturn(plan.actualSnapshot(), plan.targetSnapshot());
    when(backend.isSatisfied(any(), any(), any())).thenReturn(true);
    when(stateService.beginStep(
        ACTOR,
        work.migrationId(),
        work.executionToken(),
        1
    )).thenReturn("step-1");
    org.mockito.Mockito.doThrow(new IllegalStateException("cache unavailable"))
        .when(cacheInvalidator).invalidate(work.target().metadataTarget());

    assertThatThrownBy(() -> service.publish(
        "model-1",
        new SchemaPublicationCommand(1, 0)
    )).isInstanceOfSatisfying(DnaRuntimeException.class, exception ->
        org.assertj.core.api.Assertions.assertThat(exception.errorCode())
            .isEqualTo(DnaRuntimeErrorCode.METADATA_INVALIDATION_FAILED)
    );

    verify(stateService).fail(
        ACTOR,
        work.migrationId(),
        work.executionToken(),
        DnaRuntimeErrorCode.METADATA_INVALIDATION_FAILED
    );
    verify(stateService, never()).activate(any(), anyString(), anyString(), anyString());
  }

  @Test
  void returnsExistingMigrationWithoutReExecutingDdl() {
    PublicationWork work = work();
    SchemaPublicationView active = activeView();
    when(stateService.claim(any(), anyString(), any())).thenReturn(
        new PublicationClaim(false, work)
    );
    when(stateService.get(ACTOR.tenantId(), work.migrationId())).thenReturn(active);

    service.publish("model-1", new SchemaPublicationCommand(1, 0));

    verify(journal).prepare(work.target());
    verify(writeFences).prepare(ACTOR, work.target());
    verify(backend, never()).plan(any(), anyString(), any(), any());
    verify(stateService, never()).ready(any(), anyString(), anyString(), any());
  }

  @Test
  void resolvesPublicationByIdempotentTarget() {
    service.getByTargetVersion("model-1", 1);

    verify(stateService).getByTargetVersion("tenant-a", "model-1", 1);
  }

  @Test
  void rejectsAnInvalidTargetVersionBeforeReadingState() {
    assertThatThrownBy(() -> service.getByTargetVersion("model-1", 0))
        .isInstanceOfSatisfying(DnaRuntimeException.class, exception ->
            org.assertj.core.api.Assertions.assertThat(exception.errorCode())
                .isEqualTo(DnaRuntimeErrorCode.INVALID_ARGUMENT));

    verify(stateService, never()).getByTargetVersion(anyString(), anyString(), any(Integer.class));
  }

  @Test
  void rejectsDestructivePlansBeforePersistingOrExecutingAnyStep() {
    PublicationWork work = work();
    InitialSchemaPlan safe = plan();
    InitialSchemaPlan destructive = new InitialSchemaPlan(
        safe.dialectId(),
        safe.database(),
        safe.actualSnapshot(),
        safe.targetSnapshot(),
        SchemaMigrationRisk.DESTRUCTIVE,
        safe.steps()
    );
    when(stateService.claim(any(), anyString(), any())).thenReturn(
        new PublicationClaim(true, work)
    );
    when(backend.plan(any(), anyString(), isNull(), any())).thenReturn(destructive);

    assertThatThrownBy(() -> service.publish(
        "model-1",
        new SchemaPublicationCommand(1, 0)
    )).isInstanceOfSatisfying(DnaRuntimeException.class, exception ->
        org.assertj.core.api.Assertions.assertThat(exception.errorCode()).isEqualTo(
            DnaRuntimeErrorCode.SCHEMA_DESTRUCTIVE_CHANGE_REQUIRES_APPROVAL
        ));

    verify(stateService).fail(
        ACTOR,
        work.migrationId(),
        work.executionToken(),
        DnaRuntimeErrorCode.SCHEMA_DESTRUCTIVE_CHANGE_REQUIRES_APPROVAL
    );
    verify(stateService, never()).ready(any(), anyString(), anyString(), any());
    verify(backend, never()).execute(any(), anyString(), any());
  }

  private static PublicationWork work() {
    return new PublicationWork(
        "migration-1",
        "execution-1",
        "model-1",
        "Form A",
        "app-a",
        "form-a",
        1,
        new PhysicalSchemaTarget(
            "model-1",
            new RuntimeMetadataTarget("source-a", "catalog-a", "schema-a", "spf_table")
        ),
        null,
        new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(), List.of())
    );
  }

  private static InitialSchemaPlan plan() {
    PhysicalTableSnapshot missing = new PhysicalTableSnapshot(
        false,
        List.of(),
        List.of(),
        null,
        "0".repeat(64)
    );
    PhysicalTableSnapshot target = new PhysicalTableSnapshot(
        true,
        List.of(),
        List.of(),
        null,
        "1".repeat(64)
    );
    PlannedSchemaStep step = new PlannedSchemaStep(
        1,
        SchemaMigrationOperation.CREATE_TABLE,
        "__table__",
        "__table__",
        SchemaMigrationRisk.SAFE,
        "{}",
        "CREATE TABLE x (id varchar(36))",
        "2".repeat(64)
    );
    return new InitialSchemaPlan(
        "postgresql",
        new DatabaseIdentity("PostgreSQL", "17.0", 17, 0),
        missing,
        target,
        SchemaMigrationRisk.SAFE,
        List.of(step)
    );
  }

  private static SchemaPublicationView activeView() {
    return new SchemaPublicationView(
        "migration-1",
        "model-1",
        null,
        1,
        SchemaMigrationStatus.ACTIVE,
        SchemaMigrationRisk.SAFE,
        "postgresql",
        "0".repeat(64),
        "1".repeat(64),
        null,
        java.time.Instant.now(),
        java.time.Instant.now(),
        List.of()
    );
  }
}
