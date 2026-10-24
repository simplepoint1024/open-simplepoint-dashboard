package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordFilter;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordFilterOperator;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordPageQuery;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordSort;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeCapabilities;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.CreateApplicationFormRecordCommand;
import org.simplepoint.plugin.app.form.api.model.FormCalculation;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordLookupQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.service.DnaFormRuntimeService;
import org.simplepoint.plugin.rbac.tenant.api.service.AuditIdentityDirectory;
import org.simplepoint.plugin.workflow.api.model.WorkflowFormContract;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.RuntimeForm;

/** APP projection tests: DNA values are supplied by a port double, not proof of physical calculation. */
@SuppressWarnings("unchecked")
class FormCalculationRuntimeProjectionTest {
  private final PublishedApplicationForm form = form();
  private final PublishedApplicationFormResolver resolver = mock(PublishedApplicationFormResolver.class);
  private final DnaFormRuntimeService dna = mock(DnaFormRuntimeService.class);
  private final org.simplepoint.plugin.dna.runtime.api.service.DnaOwnedRecordService owned =
      mock(org.simplepoint.plugin.dna.runtime.api.service.DnaOwnedRecordService.class);
  private final DnaInvocationContextFactory contexts = new DnaInvocationContextFactory(new FormRequestContext());
  private final FormRuntimeDocumentCompiler runtime = new FormRuntimeDocumentCompiler();
  private ApplicationFormRecordServiceImpl records;

  @BeforeEach
  void setUp() {
    authorize(Map.of());
    when(resolver.resolve("app", "page")).thenReturn(form);
    when(resolver.capabilities(form)).thenReturn(new ApplicationFormRuntimeCapabilities(true, true, true, true));
    records = new ApplicationFormRecordServiceImpl(resolver, new FormRecordPayloadCompiler(), dna, contexts,
        mock(FormRecordInvariantValidator.class),
        new FormDetailRecordCoordinator(owned, new FormRecordPayloadCompiler(), mock(FormAttachmentReferenceValidator.class)),
        new FormRecordIdentityLabels(mock(AuditIdentityDirectory.class)));
  }

  @AfterEach
  void clear() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @Test
  void calculationIsAQueryableReadOnlyListFieldAndPermissionIdentityWithoutExposingTheExpression() throws Exception {
    var descriptor = FormPermissionDescriptorService.model(form.physicalModelId(), form.document().fields(), "");
    var field = descriptor.properties().get(RecordPermissionModel.field("amount"));
    assertThat(field).isNotNull();
    assertThat(field.readOnly()).isTrue();
    assertThat(field.type()).isEqualTo("java.math.BigDecimal");
    assertThat(field.operators()).contains("between", "than:greater");
    assertThat(descriptor.properties()).doesNotContainKeys(RecordPermissionModel.field("legacy"), "stored_amount");
    var schema = schema();
    assertThat(properties(schema).keySet()).contains("amount").doesNotContain("legacy", "stored_amount");
    assertThat((Map<String, Object>) properties(schema).get("amount"))
        .containsEntry("readOnly", true).containsEntry("x-display-only", true)
        .containsEntry("type", List.of("string", "null"));
    assertThat(queryFields(schema).keySet()).contains("amount");
    var document = runtime.compile(form);
    assertThat(document.fields().stream().filter(value -> value.fieldId().equals("amount")).findFirst().orElseThrow().calculated()).isTrue();
    assertThat(document.fields().stream().filter(value -> value.fieldId().equals("base")).findFirst().orElseThrow().calculated()).isNull();
    assertThat(document.scenes()).allSatisfy(scene -> assertThat(scene.root().children().getFirst().readOnly()).isTrue());
    String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(document);
    assertThat(json).doesNotContain("stored_amount", "[base] * 2", "calculation");
  }

  @Test
  void translatesStableCalculationQueriesAndReadsExactCommittedValues() {
    var value = new RecordView("record", 4, Map.of("stored_amount", new BigDecimal("9007199254740993.12"), "record_state", "DRAFT"));
    when(dna.pageRecords(any(), any())).thenReturn(new RecordPageView(2, 10, 23, List.of(value)));
    var page = records.page("app", "page", new ApplicationFormRecordPageQuery(2, 10, List.of("amount"),
        new ApplicationFormRecordFilter(ApplicationFormRecordFilterOperator.GREATER_THAN, "amount", List.of("1.23"), List.of()),
        List.of(new ApplicationFormRecordSort("amount", ApplicationFormRecordSort.Direction.DESC))));
    assertThat(page.total()).isEqualTo(23);
    assertThat(page.records().getFirst().values()).containsEntry("amount", "9007199254740993.12");
    var query = ArgumentCaptor.forClass(RecordPageQuery.class);
    verify(dna).pageRecords(any(), query.capture());
    assertThat(query.getValue().fields()).contains("stored_amount").doesNotContain("amount", "legacy_column");
    assertThat(query.getValue().filter().field()).isEqualTo("stored_amount");
    assertThat(query.getValue().sorts().getFirst().field()).isEqualTo("stored_amount");
    assertThat(query.getValue().page()).isEqualTo(2);
    assertThat(query.getValue().size()).isEqualTo(10);
  }

  @Test
  void includesStoredCalculationInDetailsAndKeepsTheSubmissionSnapshotIndependentOfLaterData() {
    when(dna.getRecord(any(), any())).thenReturn(new RecordView("record", 8,
        Map.of("stored_amount", new BigDecimal("123.45"), "record_state", "DRAFT")));
    assertThat(records.get("app", "page", "record").values()).containsEntry("amount", "123.45");
    var lookup = ArgumentCaptor.forClass(RecordLookupQuery.class);
    verify(dna).getRecord(any(), lookup.capture());
    assertThat(lookup.getValue().fields()).contains("stored_amount").doesNotContain("legacy_column");
    org.mockito.Mockito.clearInvocations(dna);
    when(owned.findResult(any(), any())).thenReturn(new RecordCommandResult(RecordMutationType.CREATE,
        "record", 0, 1, java.time.Instant.EPOCH));
    when(owned.snapshot(any(), any())).thenReturn(new OwnedRecordView(new RecordView("record", 0,
        Map.of("stored_amount", new BigDecimal("9007199254740993.12"), "record_state", "SUBMITTED")), Map.of()));
    var captured = records.captureCreate(form, new CreateApplicationFormRecordCommand(
        new ApplicationFormRuntimeIdentity("form", 1), "submission", FormRecordState.SUBMITTED, Map.of("base", "4503599627370496.56")));
    assertThat(captured.values()).containsEntry("amount", "9007199254740993.12");
    assertThat(captured.state()).isEqualTo(FormRecordState.SUBMITTED);
    org.mockito.Mockito.verifyNoInteractions(dna);
    org.mockito.Mockito.verify(owned, org.mockito.Mockito.never()).execute(any(), any());
  }

  @Test
  void hidesDerivedQueryMetadataWhenASourceIsRestrictedAndDoesNotOfferMaskedResultsForInference() {
    for (String policy : List.of("HIDDEN", "MASKED")) {
      authorize(Map.of(key("base"), policy));
      assertThat(properties(schema()).keySet()).doesNotContain("amount");
      assertThat(queryFields(schema()).keySet()).doesNotContain("amount");
      assertThat(runtime.compile(form).fields()).noneMatch(field -> field.fieldId().equals("amount"));
      assertThat(contexts.forForm(form).fieldPermissions()).containsEntry(key("amount"), "HIDDEN");
    }
    authorize(Map.of(key("amount"), "MASKED"));
    assertThat(properties(schema()).keySet()).contains("amount");
    assertThat(queryFields(schema()).keySet()).doesNotContain("amount");
  }

  @Test
  void mapsOnlyReadablePhysicalResultsToWorkflowVariablesAndRejectsClientOverrides() {
    var process = workflow("amount");
    FormSubmissionMapping.requireBinding(form, process, contexts.forForm(form));
    assertThat(FormSubmissionMapping.variables(form, process, Map.of("amount", "9007199254740993.12"), Map.of()))
        .containsExactlyEntriesOf(Map.of("total", "9007199254740993.12"));
    assertThatThrownBy(() -> FormSubmissionMapping.variables(form, process, Map.of("amount", "10"), Map.of("total", "1")))
        .isInstanceOf(ApplicationFormException.class);
    authorize(Map.of(key("base"), "MASKED"));
    assertThatThrownBy(() -> FormSubmissionMapping.requireBinding(form, process, contexts.forForm(form)))
        .isInstanceOfSatisfying(ApplicationFormException.class,
            error -> assertThat(error.errorCode()).isEqualTo(ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED));
    authorize(Map.of());
    assertThatThrownBy(() -> FormSubmissionMapping.requireBinding(form, workflow("legacy"), contexts.forForm(form)))
        .isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void readSupportDoesNotMakeCalculatedValuesClientWritable() {
    assertThatThrownBy(() -> records.create("app", "page", new CreateApplicationFormRecordCommand(
        new ApplicationFormRuntimeIdentity("form", 1), "forged", FormRecordState.DRAFT, Map.of("amount", "1"))))
        .isInstanceOfSatisfying(ApplicationFormException.class,
            error -> assertThat(error.errorCode()).isEqualTo(ApplicationFormErrorCode.RECORD_INVALID));
    org.mockito.Mockito.verifyNoInteractions(dna);
  }

  @Test
  void runtimePreviewUsesTrustedDnaValuesAndTheActualFieldAuthorityWithoutWriting() {
    when(owned.preview(any(), any())).thenReturn(new org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordPreview(
        Map.of("stored_amount", "9007199254740993.12", "source_amount", "4503599627370496.56", "record_state", "DRAFT"), Map.of(), 0));
    var result = records.preview("app", "page", new org.simplepoint.plugin.app.form.api.model.PreviewApplicationFormRecordCommand(
        new ApplicationFormRuntimeIdentity("form", 1), null, null, FormRecordState.DRAFT, Map.of("base", "4503599627370496.56")));
    assertThat(result.values()).containsEntry("amount", "9007199254740993.12").doesNotContainKeys("stored_amount", "record_state");
    var intent = ArgumentCaptor.forClass(org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand.class);
    var authority = ArgumentCaptor.forClass(org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext.class);
    verify(owned).preview(authority.capture(), intent.capture());
    assertThat(authority.getValue()).isEqualTo(contexts.forForm(form));
    assertThat(intent.getValue().snapshot()).isNull();
    var create = (org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation) intent.getValue().parent().mutations().getFirst();
    assertThat(create.values()).containsEntry("source_amount", "4503599627370496.56").doesNotContainKeys("stored_amount", "amount");
    org.mockito.Mockito.verifyNoInteractions(dna);
    org.mockito.Mockito.verify(owned, org.mockito.Mockito.never()).execute(any(), any());
    verify(resolver).requireAction(form, "records.create");
  }

  @Test
  void workflowPreflightUsesCalculatedValuesRatherThanStoredOutputsOrClientOverrides() {
    when(owned.preview(any(), any())).thenReturn(new org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordPreview(
        Map.of("stored_amount", "200.00000000", "source_amount", "100", "record_state", "SUBMITTED"), Map.of(), 0));
    var submit = new org.simplepoint.plugin.app.form.api.model.SubmitApplicationFormCommand(new ApplicationFormRuntimeIdentity("form", 1),
        "start", null, null, "process", 1, null, "计算审批", Map.of("base", "100"), Map.of(), List.of());
    var prospective = records.previewSubmission(form, submit);
    assertThat(FormSubmissionMapping.variables(form, workflow("amount"), prospective, Map.of())).containsEntry("total", "200");
    var intent = ArgumentCaptor.forClass(org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand.class);
    verify(owned).preview(any(), intent.capture());
    assertThat(((org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation) intent.getValue().parent().mutations().getFirst()).values())
        .containsEntry("record_state", "SUBMITTED");
    org.mockito.Mockito.verify(owned, org.mockito.Mockito.never()).execute(any(), any());
  }

  @Test
  void previewRejectsStaleRecordRuntimeAndForgedOutputsBeforeCalculation() {
    when(dna.getRecord(any(), any())).thenReturn(new RecordView("record", 2, Map.of("record_state", "DRAFT")));
    assertThatThrownBy(() -> records.preview("app", "page", new org.simplepoint.plugin.app.form.api.model.PreviewApplicationFormRecordCommand(
        new ApplicationFormRuntimeIdentity("form", 1), "record", 1L, FormRecordState.DRAFT, Map.of("base", "10"))))
        .isInstanceOfSatisfying(ApplicationFormException.class,
            error -> assertThat(error.errorCode()).isEqualTo(ApplicationFormErrorCode.RECORD_CONFLICT));
    assertThatThrownBy(() -> records.preview("app", "page", new org.simplepoint.plugin.app.form.api.model.PreviewApplicationFormRecordCommand(
        new ApplicationFormRuntimeIdentity("form", 2), null, null, FormRecordState.DRAFT, Map.of())))
        .isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(() -> records.preview("app", "page", new org.simplepoint.plugin.app.form.api.model.PreviewApplicationFormRecordCommand(
        new ApplicationFormRuntimeIdentity("form", 1), null, null, FormRecordState.DRAFT, Map.of("amount", "1"))))
        .isInstanceOf(ApplicationFormException.class);
    org.mockito.Mockito.verifyNoInteractions(owned);
  }

  private Map<String, Object> schema() {
    return (Map<String, Object>) new ApplicationFormRecordSchemaServiceImpl(resolver, runtime, contexts).schema("app", "page").get("schema");
  }

  private static Map<String, Object> properties(Map<String, Object> schema) {
    return (Map<String, Object>) schema.get("properties");
  }

  private static Map<String, Object> queryFields(Map<String, Object> schema) {
    return (Map<String, Object>) ((Map<?, ?>) schema.get("x-query")).get("fields");
  }

  private void authorize(Map<String, String> grants) {
    var context = new AuthorizationContext();
    context.setUserId("reader");
    context.setAttributes(Map.of("X-Tenant-Id", "tenant"));
    context.setResources(Set.of(form.resourceCode()));
    context.setResourceFieldPermissions(Map.of(form.resourceCode(), grants));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  private static String key(String field) {
    return RecordPermissionModel.key("model") + "#" + RecordPermissionModel.field(field);
  }

  private static RuntimeForm workflow(String field) {
    var binding = new WorkflowFormContract.Binding("simplepoint-app", "form", 1, Map.of(field, "total"));
    var contract = new WorkflowFormContract(1,
        List.of(new WorkflowFormContract.Variable("total", "审批金额", "decimal", "start", true, null)), binding, Map.of());
    return new RuntimeForm(1, contract, binding, Map.of());
  }

  private static PublishedApplicationForm form() {
    var base = field("base", "source_amount", FormStorageMode.STORED, null);
    var amount = field("amount", "stored_amount", FormStorageMode.COMPUTED, "[base] * 2");
    var legacy = field("legacy", "legacy_column", FormStorageMode.COMPUTED, null);
    var input = new FormComponentDefinition("amount-input", "field.number", "1", "amount", 24,
        null, null, null, null, false, Map.of(), List.of());
    var root = new FormComponentDefinition("root", "layout.form", "1", null, 24,
        null, null, null, null, false, Map.of(), List.of(input));
    var document = new ApplicationFormDocument("1", List.of(base, amount, legacy), List.of(),
        List.of(new FormSceneDefinition("main", "main", "Main", FormSceneType.MAIN, null, root)), List.of());
    return new PublishedApplicationForm("tenant", "app", "page", "Page", "form", "Form", 1, "app.runtime.page",
        "model", 1, "a".repeat(64), document);
  }

  private static FormFieldDefinition field(String id, String column, FormStorageMode storage, String expression) {
    return new FormFieldDefinition(id, column, id, id, FormFieldType.DECIMAL, storage,
        false, false, null, 38, 8, null, List.of(), null, null, null,
        expression == null ? null : new FormCalculation("1", expression));
  }
}
