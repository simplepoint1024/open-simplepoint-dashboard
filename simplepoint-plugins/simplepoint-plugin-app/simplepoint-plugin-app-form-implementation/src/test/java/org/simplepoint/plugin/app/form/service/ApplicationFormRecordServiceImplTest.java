package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.app.form.api.ApplicationFormApiVersion;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordFilter;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordFilterOperator;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordPageQuery;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordSort;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.CreateApplicationFormRecordCommand;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormFieldWritePolicy;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.UpdateApplicationFormRecordCommand;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.service.DnaFormRuntimeService;

class ApplicationFormRecordServiceImplTest {

  private PublishedApplicationFormResolver resolver;
  private DnaFormRuntimeService dna;
  private org.simplepoint.plugin.dna.runtime.api.service.DnaOwnedRecordService owned;
  private DnaInvocationContextFactory contexts;
  private DnaInvocationContext context;
  private DnaInvocationContext validationContext;
  private ApplicationFormRecordServiceImpl service;

  @BeforeEach
  void setUp() {
    resolver = mock(PublishedApplicationFormResolver.class);
    dna = mock(DnaFormRuntimeService.class);
    owned = mock(org.simplepoint.plugin.dna.runtime.api.service.DnaOwnedRecordService.class);
    contexts = mock(DnaInvocationContextFactory.class);
    context = mock(DnaInvocationContext.class);
    validationContext = mock(DnaInvocationContext.class);
    when(context.fieldPermissions()).thenReturn(Map.of());
    when(validationContext.fieldPermissions()).thenReturn(Map.of());
    when(resolver.resolve("app-a", "page-a")).thenReturn(form());
    when(contexts.forResource("app.runtime.page.a")).thenReturn(context);
    when(contexts.forResourceValidation("app.runtime.page.a"))
        .thenReturn(validationContext);
    service = new ApplicationFormRecordServiceImpl(
        resolver,
        new FormRecordPayloadCompiler(),
        dna,
        contexts,
        mock(FormRecordInvariantValidator.class),
        new FormDetailRecordCoordinator(owned, new FormRecordPayloadCompiler(), mock(FormAttachmentReferenceValidator.class))
    );
  }

  @Test
  void capturesCommittedValuesWithoutRereadingTheMutableRecordEvenForAFormWithoutDetails() {
    when(owned.findResult(eq(context), any())).thenReturn(new RecordCommandResult(
        RecordMutationType.CREATE, "record-a", 0, 1, Instant.EPOCH));
    when(owned.snapshot(eq(context), any())).thenReturn(new org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView(
        new RecordView("record-a", 0, Map.of("customer_name", "提交时的名字", "record_state", "SUBMITTED")), Map.of()));
    var captured = service.captureCreate(form(), new CreateApplicationFormRecordCommand(
        new ApplicationFormRuntimeIdentity("form-a", 1), "submission-a", FormRecordState.SUBMITTED,
        Map.of("name-field", "请求值")));
    assertThat(captured.values()).containsEntry("name-field", "提交时的名字");
    var intent = ArgumentCaptor.forClass(org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand.class);
    verify(owned).snapshot(eq(context), intent.capture());
    assertThat(intent.getValue().snapshot()).isNotNull();
    assertThat(intent.getValue().snapshot().details()).isEmpty();
    verifyNoInteractions(dna);
    org.mockito.Mockito.verify(owned, org.mockito.Mockito.never()).execute(any(), any());
  }

  @Test
  void neverSubstitutesMutableDataWhenTheCommittedSnapshotIsUnavailable() {
    when(owned.findResult(eq(context), any())).thenReturn(new RecordCommandResult(
        RecordMutationType.CREATE, "record-a", 0, 1, Instant.EPOCH));
    assertThatThrownBy(() -> service.captureCreate(form(), new CreateApplicationFormRecordCommand(
        new ApplicationFormRuntimeIdentity("form-a", 1), "submission-a", FormRecordState.SUBMITTED,
        Map.of("name-field", "Ada"))))
        .isInstanceOfSatisfying(ApplicationFormException.class,
            failure -> assertThat(failure.errorCode()).isEqualTo(ApplicationFormErrorCode.RECORD_OPERATION_FAILED));
    verifyNoInteractions(dna);
  }

  @Test
  void reportsTargetPolicyConflictWithoutCreatingAnotherIntent() {
    when(dna.createRecord(eq(context), any())).thenThrow(new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_WRITE_POLICY_CONFLICT, "The write policy changed"));
    assertThatThrownBy(() -> service.create("app-a", "page-a", new CreateApplicationFormRecordCommand(
        new ApplicationFormRuntimeIdentity("form-a", 1), "create-a", FormRecordState.SUBMITTED,
        Map.of("name-field", "Ada"))))
        .isInstanceOfSatisfying(ApplicationFormException.class,
            failure -> assertThat(failure.errorCode()).isEqualTo(ApplicationFormErrorCode.RECORD_CONFLICT));
    verify(dna).createRecord(eq(context), any());
  }

  @Test
  void createsThroughTheServerSelectedModelAndMapsStableFieldIdentities() {
    when(dna.createRecord(eq(context), any())).thenReturn(new RecordCommandResult(
        RecordMutationType.CREATE,
        "record-a",
        0,
        1,
        Instant.EPOCH
    ));
    when(dna.getRecord(eq(context), any())).thenReturn(new RecordView(
        "record-a",
        0,
        Map.of("customer_name", "Ada", "record_state", "SUBMITTED")
    ));

    var result = service.create(
        "app-a",
        "page-a",
        new CreateApplicationFormRecordCommand(
            new ApplicationFormRuntimeIdentity("form-a", 1),
            "create-a",
            FormRecordState.SUBMITTED,
            Map.of("name-field", "Ada")
        )
    );

    assertThat(result.currentRecord().values()).containsExactlyEntriesOf(Map.of("name-field", "Ada"));
    assertThat(result.state()).isEqualTo(FormRecordState.SUBMITTED);
    verify(resolver).requireAction(form(), "records.create");
    ArgumentCaptor<RecordCreateCommand> command = ArgumentCaptor.forClass(
        RecordCreateCommand.class
    );
    verify(dna).createRecord(eq(context), command.capture());
    assertThat(command.getValue().writePolicyVersion()).isEqualTo(7);
    assertThat(command.getValue().physicalModelId()).isEqualTo("model-a");
    assertThat(command.getValue().values()).containsExactly(
        Map.entry("customer_name", "Ada"),
        Map.entry("record_state", "SUBMITTED")
    );
  }

  @Test
  void pagesThroughDnaWithoutClientPhysicalIdentity() {
    when(dna.pageRecords(eq(context), any())).thenReturn(new RecordPageView(
        0,
        20,
        1,
        List.of(new RecordView(
            "record-a",
            2,
            Map.of("customer_name", "Ada", "record_state", "DRAFT")
        ))
    ));

    var page = service.page(
        "app-a",
        "page-a",
        new ApplicationFormRecordPageQuery(0, 20, List.of(), null, List.of())
    );

    assertThat(page.total()).isEqualTo(1);
    assertThat(page.records()).singleElement().satisfies(record -> {
      assertThat(record.id()).isEqualTo("record-a");
      assertThat(record.values()).containsEntry("name-field", "Ada");
    });
  }

  @Test
  void mapsFieldIdentityProjectionFilterAndSortBeforeQueryingDna() {
    when(dna.pageRecords(eq(context), any())).thenReturn(new RecordPageView(
        1,
        10,
        0,
        List.of()
    ));
    var filter = new ApplicationFormRecordFilter(
        ApplicationFormRecordFilterOperator.EQUALS,
        "name-field",
        List.of("Ada"),
        List.of()
    );

    service.page(
        "app-a",
        "page-a",
        new ApplicationFormRecordPageQuery(
            1,
            10,
            List.of("name-field"),
            filter,
            List.of(new ApplicationFormRecordSort(
                "name-field",
                ApplicationFormRecordSort.Direction.DESC
            ))
        )
    );

    ArgumentCaptor<RecordPageQuery> query = ArgumentCaptor.forClass(RecordPageQuery.class);
    verify(dna).pageRecords(eq(context), query.capture());
    assertThat(query.getValue().fields()).containsExactly("customer_name", "tenant_id", "created_org_dept_id", "created_by", "created_at", "updated_by", "updated_at", "deleted_at", "record_state");
    assertThat(query.getValue().filter().field()).isEqualTo("customer_name");
    assertThat(query.getValue().sorts()).singleElement().satisfies(sort -> {
      assertThat(sort.field()).isEqualTo("customer_name");
      assertThat(sort.direction().name()).isEqualTo("DESC");
    });
  }

  @Test
  void defaultProjectionOmitsFieldsHiddenByTheActivePagePolicy() {
    when(context.fieldPermissions()).thenReturn(Map.of(
        "dna.runtime.record.model-a#customer_name",
        "HIDDEN"
    ));
    when(dna.pageRecords(eq(context), any())).thenReturn(new RecordPageView(
        0,
        20,
        1,
        List.of(new RecordView("record-a", 0, Map.of("record_state", "DRAFT")))
    ));

    service.page(
        "app-a",
        "page-a",
        new ApplicationFormRecordPageQuery(0, 20, List.of(), null, List.of())
    );

    ArgumentCaptor<RecordPageQuery> query = ArgumentCaptor.forClass(RecordPageQuery.class);
    verify(dna).pageRecords(eq(context), query.capture());
    assertThat(query.getValue().fields()).containsExactly("tenant_id", "created_org_dept_id", "created_by", "created_at", "updated_by", "updated_at", "deleted_at", "record_state");
  }

  @Test
  void validatesAnUpdateAgainstTheCompleteRowButMutatesWithUserFieldPolicy() {
    when(dna.getRecord(eq(validationContext), any())).thenReturn(new RecordView(
        "record-a",
        2,
        Map.of("customer_name", "Existing", "record_state", "DRAFT")
    ));
    when(dna.getRecord(eq(context), any())).thenReturn(new RecordView(
        "record-a",
        3,
        Map.of("customer_name", "Existing", "record_state", "SUBMITTED")
    ));

    when(dna.updateRecord(eq(context), any())).thenReturn(new RecordCommandResult(
        RecordMutationType.UPDATE, "record-a", 3, 1, Instant.EPOCH));
    var result = service.update(
        "app-a",
        "page-a",
        "record-a",
        new UpdateApplicationFormRecordCommand(
            new ApplicationFormRuntimeIdentity("form-a", 1), 2, "update-a", FormRecordState.SUBMITTED, Map.of()
        )
    );

    assertThat(result.state()).isEqualTo(FormRecordState.SUBMITTED);
    verify(resolver).requireAction(form(), "records.edit");
    ArgumentCaptor<RecordUpdateCommand> command = ArgumentCaptor.forClass(
        RecordUpdateCommand.class
    );
    verify(dna).updateRecord(eq(context), command.capture());
    assertThat(command.getValue().writePolicyVersion()).isEqualTo(7);
    assertThat(command.getValue().expectedVersion()).isEqualTo(2);
    assertThat(command.getValue().idempotencyKey()).isEqualTo("update-a");
    assertThat(command.getValue().values()).containsExactlyEntriesOf(
        Map.of("record_state", "SUBMITTED")
    );
  }

  @Test
  void lockedFieldsAreRejectedBeforeDnaMutationButCommittedReceiptsStillReplay() {
    when(resolver.resolve("app-a", "page-a")).thenReturn(governedForm(FormFieldWritePolicy.UNTIL_SUBMITTED));
    when(dna.getRecord(eq(validationContext), any())).thenReturn(new RecordView(
        "record-a", 2, Map.of("customer_name", "Protected", "record_state", "SUBMITTED")));
    var command = new UpdateApplicationFormRecordCommand(new ApplicationFormRuntimeIdentity("form-a", 1),
        2, "update-protected", FormRecordState.SUBMITTED, Map.of("name-field", "New value"));

    assertThatThrownBy(() -> service.update("app-a", "page-a", "record-a", command))
        .isInstanceOfSatisfying(ApplicationFormException.class, failure -> assertThat(failure.errorCode())
            .isEqualTo(ApplicationFormErrorCode.RECORD_INVALID));
    verify(dna, org.mockito.Mockito.never()).updateRecord(any(), any());

    when(dna.findRecordUpdateResult(eq(context), any())).thenReturn(new RecordCommandResult(
        RecordMutationType.UPDATE, "record-a", 3, 1, Instant.EPOCH));
    when(dna.getRecord(eq(context), any())).thenReturn(new RecordView(
        "record-a", 9, Map.of("customer_name", "Later", "record_state", "SUBMITTED")));
    assertThat(service.update("app-a", "page-a", "record-a", command).version()).isEqualTo(3);
    verify(dna, org.mockito.Mockito.never()).updateRecord(any(), any());
  }

  @Test
  void replaysCommittedUpdateBeforeReadingLaterBusinessState() {
    when(dna.findRecordUpdateResult(eq(context), any())).thenReturn(new RecordCommandResult(
        RecordMutationType.UPDATE, "record-a", 3, 1, Instant.EPOCH));
    when(dna.getRecord(eq(context), any())).thenReturn(new RecordView(
        "record-a", 9, Map.of("customer_name", "Later edit", "record_state", "SUBMITTED")));

    var result = service.update("app-a", "page-a", "record-a", new UpdateApplicationFormRecordCommand(
        new ApplicationFormRuntimeIdentity("form-a", 1), 2, "update-a", FormRecordState.DRAFT, Map.of()));

    assertThat(result.version()).isEqualTo(3);
    assertThat(result.state()).isEqualTo(FormRecordState.DRAFT);
    assertThat(result.currentRecord().version()).isEqualTo(9);
    verify(dna, org.mockito.Mockito.never()).getRecord(eq(validationContext), any());
    verify(dna, org.mockito.Mockito.never()).updateRecord(any(), any());
  }

  @Test
  void committedUpdateRemainsAcknowledgedAfterLaterDeletionWithoutReturningRequestValues() {
    when(dna.findRecordUpdateResult(eq(context), any())).thenReturn(new RecordCommandResult(
        RecordMutationType.UPDATE, "record-a", 3, 1, Instant.EPOCH));
    when(dna.getRecord(eq(context), any())).thenThrow(new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_NOT_FOUND, "No current row"));

    var result = service.update("app-a", "page-a", "record-a", new UpdateApplicationFormRecordCommand(
        new ApplicationFormRuntimeIdentity("form-a", 1), 2, "update-a", FormRecordState.DRAFT,
        Map.of("name-field", "Original")));

    assertThat(result.version()).isEqualTo(3);
    assertThat(result.currentRecord()).isNull();
    verify(dna, org.mockito.Mockito.never()).getRecord(eq(validationContext), any());
    verify(dna, org.mockito.Mockito.never()).updateRecord(any(), any());
  }

  @Test
  void conflictingUpdateKeyCannotFallThroughToAnotherMutation() {
    when(dna.findRecordUpdateResult(eq(context), any())).thenThrow(new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_IDEMPOTENCY_CONFLICT, "Intent differs"));
    assertThatThrownBy(() -> service.update("app-a", "page-a", "record-a", new UpdateApplicationFormRecordCommand(
        new ApplicationFormRuntimeIdentity("form-a", 1), 2, "update-a", FormRecordState.DRAFT, Map.of())))
        .isInstanceOfSatisfying(ApplicationFormException.class,
            failure -> assertThat(failure.errorCode()).isEqualTo(ApplicationFormErrorCode.RECORD_CONFLICT));
    verify(dna, org.mockito.Mockito.never()).getRecord(any(), any());
    verify(dna, org.mockito.Mockito.never()).updateRecord(any(), any());
  }

  @Test
  void recoversConcurrentCommitBetweenTheFirstProbeAndBusinessValidation() {
    when(dna.findRecordUpdateResult(eq(context), any())).thenReturn(null, new RecordCommandResult(
        RecordMutationType.UPDATE, "record-a", 3, 1, Instant.EPOCH));
    when(dna.getRecord(eq(validationContext), any())).thenReturn(new RecordView(
        "record-a", 3, Map.of("customer_name", "Committed", "record_state", "SUBMITTED")));
    when(dna.getRecord(eq(context), any())).thenReturn(new RecordView(
        "record-a", 3, Map.of("customer_name", "Committed", "record_state", "SUBMITTED")));

    var result = service.update("app-a", "page-a", "record-a", new UpdateApplicationFormRecordCommand(
        new ApplicationFormRuntimeIdentity("form-a", 1), 2, "update-a", FormRecordState.SUBMITTED, Map.of()));

    assertThat(result.version()).isEqualTo(3);
    verify(dna, org.mockito.Mockito.never()).updateRecord(any(), any());
  }

  @Test
  void softDeletesThroughDnaWithTheExpectedVersion() {
    service.delete("app-a", "page-a", "record-a", 4,
        new ApplicationFormRuntimeIdentity("form-a", 1), "delete-a");

    verify(resolver).requireAction(form(), "records.delete");
    ArgumentCaptor<RecordDeleteCommand> command = ArgumentCaptor.forClass(
        RecordDeleteCommand.class
    );
    verify(dna).deleteRecord(eq(context), command.capture());
    assertThat(command.getValue().writePolicyVersion()).isEqualTo(7);
    assertThat(command.getValue().recordId()).isEqualTo("record-a");
    assertThat(command.getValue().expectedVersion()).isEqualTo(4);
    assertThat(command.getValue().idempotencyKey()).isEqualTo("delete-a");
  }

  @Test
  void rejectsMissingStaleAndReboundFormsBeforeAccessingDna() {
    ApplicationFormRuntimeIdentity[] identities = {
        null,
        new ApplicationFormRuntimeIdentity("form-a", 2),
        new ApplicationFormRuntimeIdentity("other-form", 1)
    };
    for (ApplicationFormRuntimeIdentity identity : identities) {
      assertThatThrownBy(() -> service.create("app-a", "page-a",
          new CreateApplicationFormRecordCommand(
              identity, "create-a", FormRecordState.DRAFT, Map.of()
          )))
          .isInstanceOfSatisfying(ApplicationFormException.class,
              failure -> assertThat(failure.errorCode())
                  .isEqualTo(ApplicationFormErrorCode.RUNTIME_VERSION_CONFLICT));
      assertThatThrownBy(() -> service.update("app-a", "page-a", "record-a",
          new UpdateApplicationFormRecordCommand(
              identity, 0, "update-a", FormRecordState.SUBMITTED, Map.of()
          )))
          .isInstanceOfSatisfying(ApplicationFormException.class,
              failure -> assertThat(failure.errorCode())
                  .isEqualTo(ApplicationFormErrorCode.RUNTIME_VERSION_CONFLICT));
      assertThatThrownBy(() -> service.delete("app-a", "page-a", "record-a", 0, identity, "delete-a"))
          .isInstanceOfSatisfying(ApplicationFormException.class,
              failure -> assertThat(failure.errorCode())
                  .isEqualTo(ApplicationFormErrorCode.RUNTIME_VERSION_CONFLICT));
    }
    verifyNoInteractions(dna);
  }

  @Test
  void replaysCommittedCreateBeforeApplyingNewFormWideRequiredCreateOnlyPolicy() {
    assertCreateReplay(false);
  }

  @Test
  void recoversCreateCommittedBetweenReceiptProbeAndNewPolicyValidation() {
    assertCreateReplay(true);
  }

  private void assertCreateReplay(final boolean concurrent) {
    when(resolver.resolve("app-a", "page-a")).thenReturn(governedForm(FormFieldWritePolicy.CREATE_ONLY));
    var result = new RecordCommandResult(RecordMutationType.CREATE, "record-a", 0, 1, Instant.EPOCH);
    if (concurrent) {
      when(dna.findRecordCreateResult(eq(context), any())).thenReturn(null, result);
    } else {
      when(dna.findRecordCreateResult(eq(context), any())).thenReturn(result);
    }
    when(dna.getRecord(eq(context), any())).thenThrow(new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_NOT_FOUND, "Later deletion"));
    var acknowledged = service.create("app-a", "page-a", new CreateApplicationFormRecordCommand(
        new ApplicationFormRuntimeIdentity("form-a", 1), "original-create", FormRecordState.DRAFT, Map.of()));
    assertThat(acknowledged.recordId()).isEqualTo("record-a");
    assertThat(acknowledged.version()).isZero();
    assertThat(acknowledged.currentRecord()).isNull();
    verify(dna, org.mockito.Mockito.never()).createRecord(any(), any());
  }

  private static PublishedApplicationForm governedForm(final FormFieldWritePolicy policy) {
    var page = form();
    return new PublishedApplicationForm(page.tenantId(), page.applicationId(), page.pageId(), page.pageName(),
        page.formId(), page.formName(), page.formVersion(), page.resourceCode(), page.physicalModelId(),
        page.physicalModelVersion(), page.storageChecksum(),
        FormWritePolicyProjection.apply(page.document(), document(policy)), 2);
  }

  private static PublishedApplicationForm form(final FormFieldWritePolicy policy) {
    return new PublishedApplicationForm(
        "tenant-a",
        "app-a",
        "page-a",
        "Orders",
        "form-a",
        "Order form",
        1,
        "app.runtime.page.a",
        "model-a",
        1,
        "a".repeat(64),
        document(policy),
        1,
        7
    );
  }

  private static PublishedApplicationForm form() {
    return form(null);
  }

  private static ApplicationFormDocument document(final FormFieldWritePolicy policy) {
    FormFieldDefinition name = new FormFieldDefinition(
        "name-field",
        "customer_name",
        "Name",
        "Name",
        FormFieldType.STRING,
        FormStorageMode.STORED,
        true,
        false,
        120,
        null,
        null,
        null,
        List.of(),
        policy
    );
    FormComponentDefinition input = new FormComponentDefinition(
        "name-control",
        "field.text",
        ApplicationFormApiVersion.RENDERER_V1,
        name.fieldId(),
        24,
        null,
        null,
        null,
        null,
        false,
        Map.of(),
        List.of()
    );
    FormComponentDefinition root = new FormComponentDefinition(
        "root",
        "layout.form",
        ApplicationFormApiVersion.RENDERER_V1,
        null,
        24,
        null,
        null,
        null,
        null,
        false,
        Map.of(),
        List.of(input)
    );
    return new ApplicationFormDocument(
        ApplicationFormApiVersion.DOCUMENT_V1,
        List.of(name),
        List.of(),
        List.of(new FormSceneDefinition(
            "main", "main", "Main", FormSceneType.MAIN, null, root
        )),
        List.of()
    );
  }
}
