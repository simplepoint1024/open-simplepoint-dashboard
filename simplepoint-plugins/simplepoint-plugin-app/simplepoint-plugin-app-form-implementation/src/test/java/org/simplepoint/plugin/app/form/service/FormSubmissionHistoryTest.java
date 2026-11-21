package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormSubmissionView;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.SubmitApplicationFormCommand;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordHistoryQuery;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.service.DnaOwnedRecordService;

class FormSubmissionHistoryTest {
  private static final String ROW = "11111111-1111-1111-1111-111111111111";

  @Test
  void reconstructsExactIntentAndUsesCurrentViewerForBothReads() {
    var f = new Fixture();
    var result = f.history.read(f.snapshot);
    assertThat(result.values()).containsEntry("name", "***");
    assertThat(result.values()).doesNotContainValue("original secret");
    var query = ArgumentCaptor.forClass(OwnedRecordHistoryQuery.class);
    verify(f.dna).snapshotTargets(eq(f.viewer), query.capture());
    assertThat(query.getValue().operationActorId()).isEqualTo("submitter");
    assertThat(query.getValue().command().parent().idempotencyKey())
        .isEqualTo("submission:" + "a".repeat(64));
    assertThat(query.getValue().command().parent().mutations().getFirst())
        .isEqualTo(
            new RecordCreateMutation(
                "parent",
                Map.of(
                    "stored_name",
                    "original secret",
                    FormStorageProjectionCompiler.RECORD_STATE_CODE,
                    "SUBMITTED")));
    verify(f.relations).snapshotTargets(f.form, List.of(f.target));
    verify(f.contexts).forRelations(f.form, List.of(f.targetForm));
    var read = ArgumentCaptor.forClass(OwnedRecordHistoryQuery.class);
    verify(f.dna).snapshotForParticipant(eq(f.viewer), read.capture());
    assertThat(read.getValue().withTimeout(query.getValue().timeoutMs()))
        .isEqualTo(query.getValue());
    assertThat(read.getValue().timeoutMs()).isLessThanOrEqualTo(query.getValue().timeoutMs());
    verify(f.dna, never()).execute(any(), any());
  }

  @Test
  void missingEvidenceCannotFallBackToTheUnfilteredAppSnapshot() {
    var f = new Fixture();
    when(f.dna.snapshotForParticipant(any(), any())).thenReturn(null);
    assertThatThrownBy(() -> f.history.read(f.snapshot))
        .isInstanceOf(org.simplepoint.plugin.app.form.api.error.ApplicationFormException.class);
    when(f.dna.snapshotForParticipant(any(), any()))
        .thenReturn(
            new OwnedRecordView(new RecordView(ROW, 5, Map.of("stored_name", "***")), Map.of()));
    assertThatThrownBy(() -> f.history.read(f.snapshot))
        .isInstanceOf(org.simplepoint.plugin.app.form.api.error.ApplicationFormException.class);
  }

  @Test
  void foreignTenantAndInvalidTargetDiscoveryFailBeforeTheValueRead() {
    var foreign = new Fixture();
    when(foreign.contexts.forForm(foreign.form)).thenReturn(authority("another-tenant", "viewer"));
    assertThatThrownBy(() -> foreign.history.read(foreign.snapshot))
        .isInstanceOf(org.simplepoint.plugin.app.form.api.error.ApplicationFormException.class);
    verify(foreign.dna, never()).snapshotTargets(any(), any());
    var duplicate = new Fixture();
    when(duplicate.dna.snapshotTargets(any(), any()))
        .thenReturn(List.of(duplicate.target, duplicate.target));
    assertThatThrownBy(() -> duplicate.history.read(duplicate.snapshot))
        .isInstanceOf(org.simplepoint.plugin.app.form.api.error.ApplicationFormException.class);
    verify(duplicate.dna, never()).snapshotForParticipant(any(), any());
  }

  @Test
  void mappedHistoryReconstructsTheOriginalConfirmationInsteadOfReobservingTheTarget() {
    var f = new Fixture();
    var old = f.form.document();
    var reference =
        new FormFieldDefinition(
            "reference",
            "stored_reference",
            "Reference",
            "Reference",
            FormFieldType.STRING,
            FormStorageMode.REFERENCE,
            false,
            false,
            36,
            null,
            null,
            null,
            List.of());
    var relation =
        new org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition(
            "1",
            "customer",
            org.simplepoint.plugin.app.form.api.model.relation.FormRelationCardinality.MANY_TO_ONE,
            new org.simplepoint.plugin.app.form.api.model.relation.FormRelationField(
                null, "reference"),
            new org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget(
                "app", "target", "page"),
            null,
            null,
            List.of(
                new org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping(
                    "copy",
                    "name",
                    "name",
                    org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping.Mode
                        .SERVER_SNAPSHOT,
                    org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping
                        .Conversion.IDENTITY,
                    org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping
                        .NullPolicy.CLEAR)));
    when(f.form.document())
        .thenReturn(
            new ApplicationFormDocument(
                "1",
                List.of(old.fields().getFirst(), reference),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                List.of(relation)));
    var mapping =
        new org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingIntent(
            "1",
            List.of(
                new org.simplepoint.plugin.app.form.api.model.relation
                    .FormReferenceMappingConfirmation(
                    "customer",
                    null,
                    null,
                    null,
                    new org.simplepoint.plugin.app.form.api.model.relation
                        .FormReferenceMappingObservation("a".repeat(64), ROW, 17L),
                    List.of())));
    when(f.snapshot.accepted().command().referenceMappings()).thenReturn(mapping);
    when(f.snapshot.accepted().command().values()).thenReturn(Map.of("reference", ROW));
    f.history.read(f.snapshot);
    var query = ArgumentCaptor.forClass(OwnedRecordHistoryQuery.class);
    verify(f.dna).snapshotForParticipant(eq(f.viewer), query.capture());
    assertThat(query.getValue().mapped()).isNotNull();
    assertThat(query.getValue().mapped().command()).isEqualTo(query.getValue().command());
    assertThat(query.getValue().mapped().confirmations().getFirst().observation().targetVersion())
        .isEqualTo(17);
    verify(f.dna, never()).observeMapping(any(), any());
    verify(f.dna, never()).executeMapped(any(), any());
  }

  private static final class Fixture {
    final DnaOwnedRecordService dna = mock(DnaOwnedRecordService.class);
    final DnaInvocationContextFactory contexts = mock(DnaInvocationContextFactory.class);
    final FormRelationTargetResolver relations = mock(FormRelationTargetResolver.class);
    final PublishedApplicationForm form = mock(PublishedApplicationForm.class);
    final PublishedApplicationForm targetForm = mock(PublishedApplicationForm.class);
    final RecordSnapshotTarget target =
        new RecordSnapshotTarget("old-model", "app", "old-form", "old-resource");
    final DnaInvocationContext viewer = authority("tenant", "viewer");
    final FormSubmissionHistory history;
    final FormSubmissionJournal.Snapshot snapshot;

    Fixture() {
      var field =
          new FormFieldDefinition(
              "name",
              "stored_name",
              "Name",
              "Name",
              FormFieldType.STRING,
              FormStorageMode.STORED,
              false,
              false,
              100,
              null,
              null,
              null,
              List.of());
      var document =
          new ApplicationFormDocument("1", List.of(field), List.of(), List.of(), List.of());
      when(form.tenantId()).thenReturn("tenant");
      when(form.applicationId()).thenReturn("app");
      when(form.formId()).thenReturn("form");
      when(form.resourceCode()).thenReturn("source-resource");
      when(form.physicalModelId()).thenReturn("model");
      when(form.physicalModelVersion()).thenReturn(1);
      when(form.storageChecksum()).thenReturn("a".repeat(64));
      when(form.writeAdmissionVersion()).thenReturn(1L);
      when(form.document()).thenReturn(document);
      var accepted = mock(AcceptedFormSubmission.class);
      var submitted = mock(SubmitApplicationFormCommand.class);
      when(submitted.values()).thenReturn(Map.of("name", "original secret"));
      when(accepted.form()).thenReturn(form);
      when(accepted.command()).thenReturn(submitted);
      when(accepted.dataAuthority()).thenReturn(authority("tenant", "submitter"));
      when(accepted.snapshotRequirements()).thenReturn(null);
      var view = mock(ApplicationFormSubmissionView.class);
      when(view.id()).thenReturn("a".repeat(64));
      snapshot =
          new FormSubmissionJournal.Snapshot(
              view,
              accepted,
              new ApplicationFormRecordView(
                  ROW, 0, FormRecordState.SUBMITTED, Map.of("name", "original secret")));
      when(contexts.forForm(form)).thenReturn(viewer);
      when(contexts.forRelations(form, List.of(targetForm))).thenReturn(viewer);
      when(relations.snapshotTargets(form, List.of(target))).thenReturn(List.of(targetForm));
      when(dna.snapshotTargets(any(), any())).thenReturn(List.of(target));
      when(dna.snapshotForParticipant(any(), any()))
          .thenReturn(
              new OwnedRecordView(new RecordView(ROW, 0, Map.of("stored_name", "***")), Map.of()));
      var payloads = new FormRecordPayloadCompiler();
      var details =
          new FormDetailRecordCoordinator(
              dna, payloads, mock(FormAttachmentReferenceValidator.class));
      history = new FormSubmissionHistory(dna, details, payloads, contexts, relations);
    }
  }

  private static DnaInvocationContext authority(String tenant, String actor) {
    return new DnaInvocationContext(
        tenant,
        actor,
        null,
        "source-resource",
        false,
        Set.of("source-resource", "old-resource"),
        "ALL",
        Set.of(),
        false,
        List.of(),
        Map.of());
  }
}
