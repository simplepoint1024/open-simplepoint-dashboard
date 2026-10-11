package org.simplepoint.plugin.dna.runtime.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditPublisher;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationStatus;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelBindingEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelVersionEntity;
import org.simplepoint.plugin.dna.runtime.entity.SchemaMigrationEntity;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelBindingRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelVersionRepository;
import org.simplepoint.plugin.dna.runtime.repository.SchemaMigrationRepository;
import org.simplepoint.plugin.dna.runtime.repository.SchemaMigrationStepRepository;
import org.simplepoint.plugin.dna.runtime.service.impl.SchemaPublicationStateService.PublicationClaim;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

class SchemaPublicationStateServiceTest {

  @Test
  void duplicatePublicationReturnsExistingMigrationDespiteStaleModelVersion() {
    PhysicalModelRepository modelRepository = mock(PhysicalModelRepository.class);
    PhysicalModelBindingRepository bindingRepository =
        mock(PhysicalModelBindingRepository.class);
    PhysicalModelVersionRepository versionRepository =
        mock(PhysicalModelVersionRepository.class);
    SchemaMigrationRepository migrationRepository = mock(SchemaMigrationRepository.class);
    SchemaMigrationStepRepository stepRepository = mock(SchemaMigrationStepRepository.class);
    PhysicalSchemaCodec schemaCodec = mock(PhysicalSchemaCodec.class);
    DnaRuntimeAuditPublisher auditPublisher = mock(DnaRuntimeAuditPublisher.class);
    final SchemaPublicationStateService service = new SchemaPublicationStateService(
        modelRepository,
        bindingRepository,
        versionRepository,
        migrationRepository,
        stepRepository,
        schemaCodec,
        auditPublisher
    );
    PhysicalModelEntity model = model();
    SchemaMigrationEntity migration = migration();
    PhysicalModelBindingEntity binding = binding();
    PhysicalModelVersionEntity version = version();
    when(modelRepository.findActiveByIdForUpdate("tenant-a", "model-a"))
        .thenReturn(Optional.of(model));
    when(migrationRepository.findByTargetVersion("tenant-a", "model-a", 1))
        .thenReturn(Optional.of(migration));
    when(bindingRepository.findActiveByModel("tenant-a", "model-a"))
        .thenReturn(Optional.of(binding));
    when(versionRepository.findActiveVersion("tenant-a", "model-a", 1))
        .thenReturn(Optional.of(version));
    when(schemaCodec.decode("{}"))
        .thenReturn(new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(), List.of()));

    PublicationClaim claim = service.claim(
        new RuntimeActor("tenant-a", "user-a"),
        "model-a",
        new SchemaPublicationCommand(1, 0)
    );

    assertThat(claim.execute()).isFalse();
    assertThat(claim.work().migrationId()).isEqualTo("migration-a");
    verify(migrationRepository, never()).saveAndFlush(any());
  }

  @Test
  void staleRecoveryFencesTheOldWorkerAndMovesTheModelToFailed() {
    PhysicalModelRepository modelRepository = mock(PhysicalModelRepository.class);
    PhysicalModelBindingRepository bindingRepository = mock(PhysicalModelBindingRepository.class);
    PhysicalModelVersionRepository versionRepository =
        mock(PhysicalModelVersionRepository.class);
    SchemaMigrationRepository migrationRepository = mock(SchemaMigrationRepository.class);
    SchemaMigrationStepRepository stepRepository = mock(SchemaMigrationStepRepository.class);
    PhysicalSchemaCodec schemaCodec = mock(PhysicalSchemaCodec.class);
    DnaRuntimeAuditPublisher auditPublisher = mock(DnaRuntimeAuditPublisher.class);
    final SchemaPublicationStateService service = new SchemaPublicationStateService(
        modelRepository,
        bindingRepository,
        versionRepository,
        migrationRepository,
        stepRepository,
        schemaCodec,
        auditPublisher
    );
    PhysicalModelEntity model = model();
    model.setStatus(PhysicalModelStatus.MIGRATING);
    SchemaMigrationEntity migration = migration();
    migration.setStatus(SchemaMigrationStatus.MIGRATING);
    migration.setExecutionToken("old-worker");
    migration.setStartedAt(Instant.parse("2026-09-30T00:00:00Z"));
    migration.setUpdatedAt(Instant.parse("2026-09-30T00:01:00Z"));
    when(migrationRepository.findActiveByIdForUpdate("tenant-a", "migration-a"))
        .thenReturn(Optional.of(migration));
    when(modelRepository.findActiveByIdForUpdate("tenant-a", "model-a"))
        .thenReturn(Optional.of(model));
    when(bindingRepository.findActiveByModel("tenant-a", "model-a"))
        .thenReturn(Optional.of(binding()));
    when(stepRepository.findByMigration("tenant-a", "migration-a")).thenReturn(List.of());

    var recovered = service.recoverStale(
        new RuntimeActor("tenant-a", "operator-a"),
        "migration-a",
        9,
        Instant.parse("2026-09-30T00:02:00Z")
    );

    assertThat(recovered.status()).isEqualTo(SchemaMigrationStatus.FAILED);
    assertThat(migration.getErrorCode()).isEqualTo("SCHEMA_PUBLICATION_INTERRUPTED");
    assertThat(migration.getExecutionToken()).isNotEqualTo("old-worker");
    assertThat(model.getStatus()).isEqualTo(PhysicalModelStatus.FAILED);
    verify(auditPublisher).publish(any());
    assertThatThrownBy(() -> service.beginStep(
        new RuntimeActor("tenant-a", "user-a"),
        "migration-a",
        "old-worker",
        1
    )).isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void activePublicationCannotBeRecoveredBeforeTheInactivityThreshold() {
    PhysicalModelRepository modelRepository = mock(PhysicalModelRepository.class);
    PhysicalModelBindingRepository bindingRepository = mock(PhysicalModelBindingRepository.class);
    SchemaMigrationRepository migrationRepository = mock(SchemaMigrationRepository.class);
    SchemaMigrationStepRepository stepRepository = mock(SchemaMigrationStepRepository.class);
    final SchemaPublicationStateService service = new SchemaPublicationStateService(
        modelRepository,
        bindingRepository,
        mock(PhysicalModelVersionRepository.class),
        migrationRepository,
        stepRepository,
        mock(PhysicalSchemaCodec.class),
        mock(DnaRuntimeAuditPublisher.class)
    );
    PhysicalModelEntity model = model();
    model.setStatus(PhysicalModelStatus.VALIDATING);
    SchemaMigrationEntity migration = migration();
    migration.setStatus(SchemaMigrationStatus.VALIDATING);
    migration.setExecutionToken("active-worker");
    migration.setStartedAt(Instant.parse("2026-09-30T00:02:00Z"));
    migration.setUpdatedAt(Instant.parse("2026-09-30T00:02:00Z"));
    when(migrationRepository.findActiveByIdForUpdate("tenant-a", "migration-a"))
        .thenReturn(Optional.of(migration));
    when(modelRepository.findActiveByIdForUpdate("tenant-a", "model-a"))
        .thenReturn(Optional.of(model));

    assertThatThrownBy(() -> service.recoverStale(
        new RuntimeActor("tenant-a", "operator-a"),
        "migration-a",
        9,
        Instant.parse("2026-09-30T00:01:00Z")
    )).isInstanceOf(DnaRuntimeException.class);

    assertThat(migration.getStatus()).isEqualTo(SchemaMigrationStatus.VALIDATING);
    assertThat(migration.getExecutionToken()).isEqualTo("active-worker");
  }

  private static PhysicalModelEntity model() {
    PhysicalModelEntity model = new PhysicalModelEntity();
    model.setId("model-a");
    model.setTenantId("tenant-a");
    model.setAppId("app-a");
    model.setFormId("form-a");
    model.setDisplayName("Form A");
    model.setStatus(PhysicalModelStatus.ACTIVE);
    model.setLatestVersion(1);
    model.setActiveVersion(1);
    model.setLockVersion(9);
    return model;
  }

  private static SchemaMigrationEntity migration() {
    SchemaMigrationEntity migration = new SchemaMigrationEntity();
    migration.setId("migration-a");
    migration.setTenantId("tenant-a");
    migration.setPhysicalModelId("model-a");
    migration.setTargetVersion(1);
    migration.setStatus(SchemaMigrationStatus.ACTIVE);
    return migration;
  }

  private static PhysicalModelBindingEntity binding() {
    PhysicalModelBindingEntity binding = new PhysicalModelBindingEntity();
    binding.setDataSourceId("source-a");
    binding.setCatalogName("catalog-a");
    binding.setSchemaName("schema-a");
    binding.setTableName("spf_model_a");
    return binding;
  }

  private static PhysicalModelVersionEntity version() {
    PhysicalModelVersionEntity version = new PhysicalModelVersionEntity();
    version.setSchemaJson("{}");
    return version;
  }
}
