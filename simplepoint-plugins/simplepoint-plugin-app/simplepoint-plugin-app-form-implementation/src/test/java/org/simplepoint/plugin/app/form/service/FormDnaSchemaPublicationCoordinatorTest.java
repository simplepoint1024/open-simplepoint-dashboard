package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.service.FormDnaSchemaPublicationCoordinator.DnaSchemaPreparationRequest;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationRisk;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationStatus;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPlanView;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPublicationView;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelVersionView;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelView;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.api.service.DnaFormRuntimeService;

class FormDnaSchemaPublicationCoordinatorTest {

  private static final String TARGET_CHECKSUM = "a".repeat(64);
  private static final PhysicalSchemaDefinition SCHEMA = new PhysicalSchemaDefinition(
      DnaRuntimeApiVersion.CURRENT,
      List.of(),
      List.of()
  );

  private DnaFormRuntimeService dna;
  private FormDnaSchemaPublicationCoordinator coordinator;

  @BeforeEach
  void setUp() {
    dna = mock(DnaFormRuntimeService.class);
    coordinator = new FormDnaSchemaPublicationCoordinator(dna);
  }

  @Test
  void registersAndPublishesTheFirstSafeSchema() {
    PhysicalModelView draft = model(
        PhysicalModelStatus.DRAFT,
        1,
        null,
        0,
        TARGET_CHECKSUM
    );
    when(dna.registerModel(any(), any())).thenReturn(draft);
    when(dna.planSchema(context(), "model-a", 1)).thenReturn(plan(SchemaMigrationRisk.SAFE));
    when(dna.publishSchema(any(), any(), any())).thenReturn(publication(
        SchemaMigrationStatus.ACTIVE
    ));

    var result = coordinator.prepare(context(), request(null));

    assertThat(result.active()).isTrue();
    assertThat(result.physicalModelId()).isEqualTo("model-a");
    assertThat(result.physicalModelVersion()).isEqualTo(1);
    assertThat(result.migrationId()).isEqualTo("migration-a");
  }

  @Test
  void recoversRegistrationAfterResponseLoss() {
    PhysicalModelView active = model(
        PhysicalModelStatus.ACTIVE,
        1,
        1,
        2,
        TARGET_CHECKSUM
    );
    when(dna.registerModel(any(), any())).thenThrow(new IllegalStateException("timeout"));
    when(dna.getModelByForm(context(), "app-a", "form-a")).thenReturn(active);

    var result = coordinator.prepare(context(), request(null));

    assertThat(result.active()).isTrue();
    assertThat(result.migrationId()).isNull();
    verify(dna, never()).planSchema(any(), any(), any(Integer.class));
  }

  @Test
  void recoversVersionCreationAndPublicationAfterResponsesAreLost() {
    PhysicalModelView current = model(
        PhysicalModelStatus.ACTIVE,
        1,
        1,
        3,
        "b".repeat(64)
    );
    PhysicalModelView target = model(
        PhysicalModelStatus.DRAFT,
        2,
        1,
        4,
        TARGET_CHECKSUM
    );
    when(dna.getModel(context(), "model-a")).thenReturn(current, target);
    when(dna.createModelVersion(any(), any(), any()))
        .thenThrow(new IllegalStateException("version response lost"));
    when(dna.planSchema(context(), "model-a", 2)).thenReturn(plan(SchemaMigrationRisk.SAFE));
    when(dna.publishSchema(any(), any(), any()))
        .thenThrow(new IllegalStateException("publication response lost"));
    when(dna.getSchemaPublicationByTarget(context(), "model-a", 2)).thenReturn(publication(
        SchemaMigrationStatus.ACTIVE,
        2
    ));

    var result = coordinator.prepare(context(), request("model-a"));

    assertThat(result.active()).isTrue();
    assertThat(result.physicalModelVersion()).isEqualTo(2);
    assertThat(result.migrationId()).isEqualTo("migration-a");
  }

  @Test
  void retriesAnExistingFailedPublicationWithTheCurrentModelLock() {
    PhysicalModelView draft = model(
        PhysicalModelStatus.DRAFT,
        1,
        null,
        5,
        TARGET_CHECKSUM
    );
    PhysicalModelView failed = model(
        PhysicalModelStatus.FAILED,
        1,
        null,
        6,
        TARGET_CHECKSUM
    );
    when(dna.getModel(context(), "model-a")).thenReturn(draft, failed);
    when(dna.planSchema(context(), "model-a", 1)).thenReturn(plan(SchemaMigrationRisk.SAFE));
    when(dna.publishSchema(any(), any(), any())).thenReturn(publication(
        SchemaMigrationStatus.FAILED
    ));
    when(dna.retrySchemaPublication(context(), "migration-a", 6)).thenReturn(publication(
        SchemaMigrationStatus.ACTIVE
    ));

    var result = coordinator.prepare(context(), request("model-a"));

    assertThat(result.active()).isTrue();
    verify(dna).retrySchemaPublication(context(), "migration-a", 6);
  }

  @Test
  void fencesAndRetriesStaleNonterminalPublication() {
    PhysicalModelView migrating = model(
        PhysicalModelStatus.MIGRATING,
        1,
        null,
        7,
        TARGET_CHECKSUM
    );
    PhysicalModelView failed = model(
        PhysicalModelStatus.FAILED,
        1,
        null,
        8,
        TARGET_CHECKSUM
    );
    when(dna.getModel(context(), "model-a")).thenReturn(migrating, migrating, failed);
    when(dna.planSchema(context(), "model-a", 1)).thenReturn(plan(SchemaMigrationRisk.SAFE));
    when(dna.publishSchema(any(), any(), any())).thenReturn(publication(
        SchemaMigrationStatus.MIGRATING
    ));
    when(dna.recoverSchemaPublication(context(), "migration-a", 7)).thenReturn(publication(
        SchemaMigrationStatus.FAILED
    ));
    when(dna.retrySchemaPublication(context(), "migration-a", 8)).thenReturn(publication(
        SchemaMigrationStatus.ACTIVE
    ));

    var result = coordinator.prepare(context(), request("model-a", true));

    assertThat(result.active()).isTrue();
    verify(dna).recoverSchemaPublication(context(), "migration-a", 7);
    verify(dna).retrySchemaPublication(context(), "migration-a", 8);
  }

  @Test
  void requiresReviewForConditionalMigration() {
    PhysicalModelView draft = model(
        PhysicalModelStatus.DRAFT,
        1,
        null,
        0,
        TARGET_CHECKSUM
    );
    when(dna.getModel(context(), "model-a")).thenReturn(draft);
    when(dna.planSchema(context(), "model-a", 1)).thenReturn(plan(
        SchemaMigrationRisk.CONDITIONAL
    ));

    assertThatThrownBy(() -> coordinator.prepare(context(), request("model-a")))
        .isInstanceOfSatisfying(ApplicationFormException.class, exception ->
            assertThat(exception.errorCode()).isEqualTo(
                ApplicationFormErrorCode.PUBLICATION_REVIEW_REQUIRED
            ));

    verify(dna, never()).publishSchema(any(), any(), any());
  }

  private static DnaSchemaPreparationRequest request(final String modelId) {
    return request(modelId, false);
  }

  private static DnaSchemaPreparationRequest request(
      final String modelId,
      final boolean recoverStale
  ) {
    return new DnaSchemaPreparationRequest(
        "app-a",
        "form-a",
        "Orders",
        "default",
        modelId,
        SCHEMA,
        TARGET_CHECKSUM,
        recoverStale
    );
  }

  private static DnaInvocationContext context() {
    return new DnaInvocationContext(
        "tenant-a",
        "user-a",
        null,
        null,
        true,
        Set.of(),
        null,
        Set.of(),
        false,
        List.of(),
        Map.of()
    );
  }

  private static PhysicalModelView model(
      final PhysicalModelStatus status,
      final int latestVersion,
      final Integer activeVersion,
      final long lockVersion,
      final String checksum
  ) {
    return new PhysicalModelView(
        "model-a",
        "app-a",
        "form-a",
        "Orders",
        status,
        latestVersion,
        activeVersion,
        lockVersion,
        new PhysicalModelVersionView(
            "model-version-" + latestVersion,
            "model-a",
            latestVersion,
            SCHEMA,
            checksum,
            "user-a",
            Instant.EPOCH
        ),
        Instant.EPOCH,
        Instant.EPOCH
    );
  }

  private static SchemaPlanView plan(final SchemaMigrationRisk risk) {
    return new SchemaPlanView(
        "model-a",
        1,
        "postgresql",
        "0".repeat(64),
        "1".repeat(64),
        risk,
        List.of()
    );
  }

  private static SchemaPublicationView publication(final SchemaMigrationStatus status) {
    return publication(status, 1);
  }

  private static SchemaPublicationView publication(
      final SchemaMigrationStatus status,
      final int targetVersion
  ) {
    return new SchemaPublicationView(
        "migration-a",
        "model-a",
        null,
        targetVersion,
        status,
        SchemaMigrationRisk.SAFE,
        "postgresql",
        "0".repeat(64),
        "1".repeat(64),
        status == SchemaMigrationStatus.FAILED ? "TEST_FAILURE" : null,
        Instant.EPOCH,
        status.terminal() ? Instant.EPOCH : null,
        List.of()
    );
  }
}
