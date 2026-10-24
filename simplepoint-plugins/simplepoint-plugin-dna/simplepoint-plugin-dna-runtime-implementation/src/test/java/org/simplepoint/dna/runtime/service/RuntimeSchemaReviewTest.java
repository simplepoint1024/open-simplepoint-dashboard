package org.simplepoint.dna.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationRisk;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPlanView;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.service.SchemaPublicationService;
import org.springframework.security.access.AccessDeniedException;

class RuntimeSchemaReviewTest {
  private final RuntimeGovernanceCatalog catalog = mock(RuntimeGovernanceCatalog.class);
  private final SchemaPublicationService publications = mock(SchemaPublicationService.class);
  private final RuntimeSchemaReview service = new RuntimeSchemaReview(catalog, publications);
  private final RuntimeSchemaReview.Command command =
      new RuntimeSchemaReview.Command(2, 7, "a".repeat(64), "b".repeat(64));

  @BeforeEach
  void setup() {
    authorize(true);
    when(catalog.model("model")).thenReturn(model(7, 2, 1));
    when(catalog.plan("model", 2)).thenReturn(plan(SchemaMigrationRisk.CONDITIONAL, "a", "b"));
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @Test
  void executesTheInspectedConditionalPlanThroughTheAuthoritativeMigrationService() {
    var review = service.inspect("model");
    assertThat(review.canPublish()).isTrue();
    assertThat(review.expectedVersion()).isEqualTo(7);
    service.publish("model", command);
    verify(publications).publish("model", new SchemaPublicationCommand(2, 7));
  }

  @Test
  void readOnlyReviewerCannotUseThePublicWriteOrForgeItsCapability() {
    authorize(false);
    assertThat(service.inspect("model").canPublish()).isFalse();
    assertThatThrownBy(() -> service.publish("model", command))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(publications);
  }

  @Test
  void changedRevisionOrNewTargetRequiresAnotherReview() {
    service.inspect("model");
    when(catalog.model("model")).thenReturn(model(8, 2, 1));
    rejected(DnaRuntimeErrorCode.PHYSICAL_MODEL_VERSION_CONFLICT);
    when(catalog.model("model")).thenReturn(model(7, 3, 1));
    rejected(DnaRuntimeErrorCode.PHYSICAL_MODEL_VERSION_CONFLICT);
  }

  @Test
  void physicalOrLogicalDriftCannotReuseTheOldReview() {
    when(catalog.plan("model", 2)).thenReturn(plan(SchemaMigrationRisk.CONDITIONAL, "c", "b"));
    rejected(DnaRuntimeErrorCode.SCHEMA_DRIFT_DETECTED);
    when(catalog.plan("model", 2)).thenReturn(plan(SchemaMigrationRisk.CONDITIONAL, "a", "c"));
    rejected(DnaRuntimeErrorCode.SCHEMA_DRIFT_DETECTED);
  }

  @Test
  void destructivePlansRemainUnavailableEvenToAnAuthorizedPublisher() {
    when(catalog.plan("model", 2)).thenReturn(plan(SchemaMigrationRisk.DESTRUCTIVE, "a", "b"));
    assertThat(service.inspect("model").canPublish()).isFalse();
    rejected(DnaRuntimeErrorCode.SCHEMA_DESTRUCTIVE_CHANGE_REQUIRES_APPROVAL);
  }

  @Test
  void scopedModelFailureAndTargetPrecheckFailureCannotBeBypassed() {
    when(catalog.model("foreign"))
        .thenThrow(
            new DnaRuntimeException(DnaRuntimeErrorCode.PHYSICAL_MODEL_NOT_FOUND, "Not found"));
    assertThatThrownBy(() -> service.inspect("foreign")).isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> service.publish("foreign", command))
        .isInstanceOf(DnaRuntimeException.class);
    when(catalog.plan("model", 2))
        .thenThrow(
            new DnaRuntimeException(DnaRuntimeErrorCode.SCHEMA_PRECHECK_FAILED, "Existing nulls"));
    rejected(DnaRuntimeErrorCode.SCHEMA_PRECHECK_FAILED);
  }

  @Test
  void activeVersionsExposeNoNewExecutionAndInvalidCommandsAreBounded() {
    when(catalog.model("model")).thenReturn(model(7, 2, 2));
    assertThat(service.inspect("model").canPublish()).isFalse();
    rejected(DnaRuntimeErrorCode.PHYSICAL_MODEL_STATE_CONFLICT);
    assertThatThrownBy(() -> new RuntimeSchemaReview.Command(0, 7, "a".repeat(64), "b".repeat(64)))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> new RuntimeSchemaReview.Command(2, -1, "a".repeat(64), "b".repeat(64)))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () -> new RuntimeSchemaReview.Command(2, 7, "not-a-checksum", "b".repeat(64)))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> service.publish("model", null))
        .isInstanceOf(DnaRuntimeException.class);
    verifyNoInteractions(publications);
  }

  private void rejected(final DnaRuntimeErrorCode code) {
    assertThatThrownBy(() -> service.publish("model", command))
        .isInstanceOfSatisfying(
            DnaRuntimeException.class, error -> assertThat(error.errorCode()).isEqualTo(code));
    verifyNoInteractions(publications);
  }

  private static RuntimeGovernanceCatalog.ModelDetail model(
      final long lock, final int latest, final int active) {
    return new RuntimeGovernanceCatalog.ModelDetail(
        new RuntimeGovernanceCatalog.ModelSummary(
            "model",
            "Reviewed model",
            "app",
            "form",
            "ACTIVE",
            latest,
            active,
            lock,
            Instant.EPOCH),
        Instant.EPOCH,
        "{}");
  }

  private static SchemaPlanView plan(
      final SchemaMigrationRisk risk, final String actual, final String target) {
    return new SchemaPlanView(
        "model", 2, "postgresql", actual.repeat(64), target.repeat(64), risk, List.of());
  }

  private static void authorize(final boolean publish) {
    var context = new AuthorizationContext();
    context.setResources(
        publish
            ? List.of("dna.runtime.models.view", "dna.runtime.models.publish")
            : List.of("dna.runtime.models.view"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }
}
