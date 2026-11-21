package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.CreateApplicationFormRecordCommand;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.PreviewApplicationFormRecordCommand;
import org.simplepoint.plugin.app.form.api.model.UpdateApplicationFormRecordCommand;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingConfirmation;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingIntent;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingObservation;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationCardinality;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationField;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.record.MappedOwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordPreview;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope;
import org.simplepoint.plugin.dna.runtime.api.service.DnaFormRuntimeService;
import org.simplepoint.plugin.dna.runtime.api.service.DnaOwnedRecordService;

class ApplicationFormMappedRecordTest {
  private static final String TARGET = "11111111-1111-1111-1111-111111111111";
  private static final String ROW = "22222222-2222-2222-2222-222222222222";
  private static final ApplicationFormRuntimeIdentity RUNTIME =
      new ApplicationFormRuntimeIdentity("form", 1);
  private static final FormReferenceMappingIntent MAPPING =
      new FormReferenceMappingIntent(
          "1",
          List.of(
              new FormReferenceMappingConfirmation(
                  "customer",
                  null,
                  null,
                  null,
                  new FormReferenceMappingObservation("a".repeat(64), TARGET, 4L),
                  List.of())));

  @Test
  void capturedCreateUsesMappedReceiptWriteAndImmutableSnapshotAndRejectsForgedCopies() {
    var f = new Fixture();
    when(f.owned.executeMapped(any(), any())).thenReturn(result(RecordMutationType.CREATE, 0));
    when(f.owned.snapshotMapped(any(), any())).thenReturn(snapshot(0));
    var result = f.records.captureCreate(f.form, create(Map.of("reference", TARGET)));
    assertThat(result.values()).containsEntry("copied", "captured");
    var request = ArgumentCaptor.forClass(MappedOwnedRecordCommand.class);
    verify(f.owned).executeMapped(eq(f.context), request.capture());
    assertThat(request.getValue().confirmations().getFirst().observation().targetVersion())
        .isEqualTo(4);
    assertThat(request.getValue().command().parent().mutations().getFirst())
        .isInstanceOfSatisfying(
            org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation.class,
            mutation ->
                assertThat(mutation.values())
                    .containsEntry("reference", TARGET)
                    .doesNotContainKey("copied"));
    verify(f.owned).snapshotMapped(f.context, request.getValue());
    var history =
        ArgumentCaptor.forClass(
            org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordHistoryQuery.class);
    verify(f.owned).snapshotTargets(eq(f.context), history.capture());
    assertThat(history.getValue().mapped()).isEqualTo(request.getValue());
    assertThat(history.getValue().operationActorId()).isEqualTo("actor");
    assertThat(history.getValue().recordId()).isEqualTo(ROW);
    verify(f.owned, never()).execute(any(), any());
    verifyNoInteractions(f.dna);
    var forged = new Fixture();
    assertThatThrownBy(
            () ->
                forged.records.captureCreate(
                    forged.form, create(Map.of("reference", TARGET, "copied", "forged"))))
        .isInstanceOf(ApplicationFormException.class);
    verifyNoInteractions(forged.owned, forged.dna);
  }

  @Test
  void inverseCapturedReplayUsesOriginalScopeAndNeverDowngradesToOrdinaryRecovery() {
    var f = new Fixture();
    var scope =
        new RelatedRecordScope(
            "1", "customer", "target-model", TARGET, RelatedRecordScope.Mode.MAINTAIN, null);
    when(f.owned.findMappedResult(any(), any())).thenReturn(result(RecordMutationType.UPDATE, 5));
    when(f.owned.snapshotMapped(any(), any())).thenReturn(snapshot(5));
    var result =
        f.records.captureRelatedUpdate(
            f.form,
            ROW,
            new UpdateApplicationFormRecordCommand(
                RUNTIME,
                4,
                "mapped-update",
                FormRecordState.SUBMITTED,
                Map.of("reference", TARGET),
                MAPPING),
            null,
            scope);
    assertThat(result.version()).isEqualTo(5);
    var request = ArgumentCaptor.forClass(MappedOwnedRecordCommand.class);
    verify(f.owned).snapshotMapped(eq(f.context), request.capture());
    assertThat(request.getValue().relatedScope()).isEqualTo(scope);
    verify(f.owned, never()).executeMapped(any(), any());
    verify(f.owned, never()).findResult(any(), any());
    verify(f.owned, never()).findRelatedResult(any(), any());
    verifyNoInteractions(f.mappingAuthority);
    verify(f.owned, never()).currentTargets(any(), any());
    verifyNoInteractions(f.dna);
  }

  @Test
  void ambiguousWriteFailureCanOnlyRecoverTheExactMappedReceipt() {
    var f = new Fixture();
    when(f.owned.findMappedResult(any(), any()))
        .thenReturn(null, result(RecordMutationType.CREATE, 0));
    when(f.owned.executeMapped(any(), any())).thenThrow(new IllegalStateException("lost response"));
    when(f.owned.snapshotMapped(any(), any())).thenReturn(snapshot(0));
    assertThat(f.records.captureCreate(f.form, create(Map.of("reference", TARGET))).id())
        .isEqualTo(ROW);
    var request = ArgumentCaptor.forClass(MappedOwnedRecordCommand.class);
    verify(f.owned, org.mockito.Mockito.times(2))
        .findMappedResult(eq(f.context), request.capture());
    assertThat(request.getAllValues().get(0)).isEqualTo(request.getAllValues().get(1));
    verify(f.owned, never()).findResult(any(), any());
    verifyNoInteractions(f.dna);
  }

  @Test
  void previewUsesAuthoritativeMappingWithoutWriting() {
    var f = new Fixture();
    when(f.owned.previewMapped(any(), any()))
        .thenReturn(
            new OwnedRecordPreview(Map.of("reference", TARGET, "copied", "preview"), Map.of(), 7));
    var result =
        f.records.preview(
            "app",
            "page",
            new PreviewApplicationFormRecordCommand(
                RUNTIME,
                null,
                null,
                FormRecordState.SUBMITTED,
                Map.of("reference", TARGET),
                MAPPING));
    assertThat(result.values()).containsEntry("copied", "preview");
    verify(f.owned, never()).executeMapped(any(), any());
    verify(f.owned, never()).preview(any(), any());
    verifyNoInteractions(f.dna);
  }

  private static CreateApplicationFormRecordCommand create(Map<String, Object> values) {
    return new CreateApplicationFormRecordCommand(
        RUNTIME, "mapped-create", FormRecordState.SUBMITTED, values, MAPPING);
  }

  private static RecordCommandResult result(RecordMutationType type, long version) {
    return new RecordCommandResult(type, ROW, version, 1, Instant.EPOCH);
  }

  private static OwnedRecordView snapshot(long version) {
    return new OwnedRecordView(
        new RecordView(
            ROW,
            version,
            Map.of("reference", TARGET, "copied", "captured", "record_state", "SUBMITTED")),
        Map.of());
  }

  private static final class Fixture {
    final PublishedApplicationFormResolver resolver = mock(PublishedApplicationFormResolver.class);
    final DnaOwnedRecordService owned = mock(DnaOwnedRecordService.class);
    final DnaFormRuntimeService dna = mock(DnaFormRuntimeService.class);
    final DnaInvocationContextFactory contexts = mock(DnaInvocationContextFactory.class);
    final FormReferenceMappingAuthority mappingAuthority =
        mock(FormReferenceMappingAuthority.class);
    final DnaInvocationContext context = mock(DnaInvocationContext.class);
    final PublishedApplicationForm form = mock(PublishedApplicationForm.class);
    final ApplicationFormRecordServiceImpl records;

    Fixture() {
      var reference =
          new FormFieldDefinition(
              "reference",
              "reference",
              "Customer",
              "Customer",
              FormFieldType.STRING,
              FormStorageMode.REFERENCE,
              false,
              false,
              36,
              null,
              null,
              null,
              List.of());
      var copied =
          new FormFieldDefinition(
              "copied",
              "copied",
              "Name",
              "Name",
              FormFieldType.STRING,
              FormStorageMode.STORED,
              true,
              false,
              100,
              null,
              null,
              null,
              List.of());
      var relation =
          new FormRelationDefinition(
              "1",
              "customer",
              FormRelationCardinality.MANY_TO_ONE,
              new FormRelationField(null, "reference"),
              new FormRelationTarget("app", "target", "target-page"),
              null,
              null,
              List.of(
                  new FormReferenceMapping(
                      "copy",
                      "name",
                      "copied",
                      FormReferenceMapping.Mode.SERVER_SNAPSHOT,
                      FormReferenceMapping.Conversion.IDENTITY,
                      FormReferenceMapping.NullPolicy.CLEAR)));
      when(form.document())
          .thenReturn(
              new ApplicationFormDocument(
                  "1",
                  List.of(reference, copied),
                  List.of(),
                  List.of(),
                  List.of(),
                  List.of(),
                  null,
                  null,
                  List.of(relation)));
      when(form.formId()).thenReturn("form");
      when(form.applicationId()).thenReturn("app");
      when(context.actorId()).thenReturn("actor");
      when(form.formVersion()).thenReturn(1);
      when(form.physicalModelId()).thenReturn("model");
      when(form.physicalModelVersion()).thenReturn(1);
      when(form.storageChecksum()).thenReturn("a".repeat(64));
      when(form.writeAdmissionVersion()).thenReturn(7L);
      when(form.resourceCode()).thenReturn("resource");
      when(form.tenantId()).thenReturn("tenant");
      when(resolver.resolve("app", "page")).thenReturn(form);
      when(contexts.forWrite(form)).thenReturn(context);
      when(mappingAuthority.forWrite(eq(form), any())).thenReturn(context);
      when(contexts.forForm(form)).thenReturn(context);
      when(contexts.forRelations(eq(form), any())).thenReturn(context);
      when(context.fieldPermissions()).thenReturn(Map.of());
      var payloads = new FormRecordPayloadCompiler();
      records =
          new ApplicationFormRecordServiceImpl(
              resolver,
              payloads,
              dna,
              contexts,
              mock(FormRecordInvariantValidator.class),
              new FormDetailRecordCoordinator(
                  owned, payloads, mock(FormAttachmentReferenceValidator.class)),
              mock(FormRecordIdentityLabels.class),
              new FormRecordReadAuthority(owned, contexts, mock(FormRelationTargetResolver.class)),
              mappingAuthority);
    }
  }
}
