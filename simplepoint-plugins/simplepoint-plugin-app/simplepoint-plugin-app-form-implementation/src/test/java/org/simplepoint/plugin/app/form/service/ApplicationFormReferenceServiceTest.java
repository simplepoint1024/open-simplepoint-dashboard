package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeView;
import org.simplepoint.plugin.app.form.api.model.FormConditionOperator;
import org.simplepoint.plugin.app.form.api.model.FormDetailDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceContextFilter;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceOption;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceSearchContext;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceView;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationCardinality;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationField;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueConditionOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.service.DnaFormRuntimeService;

class ApplicationFormReferenceServiceTest {
  private static final org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity
      RUNTIME =
          new org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity("trades", 1);
  private final PublishedApplicationFormResolver forms =
      mock(PublishedApplicationFormResolver.class);
  private final FormRelationTargetResolver relations = mock(FormRelationTargetResolver.class);
  private final DnaInvocationContextFactory contexts = mock(DnaInvocationContextFactory.class);
  private final DnaFormRuntimeService dna = mock(DnaFormRuntimeService.class);
  private final ApplicationFormRecordServiceImpl records =
      mock(ApplicationFormRecordServiceImpl.class);
  private final ApplicationFormRuntimeServiceImpl runtime =
      mock(ApplicationFormRuntimeServiceImpl.class);
  private final DnaInvocationContext sourceContext = mock(DnaInvocationContext.class);
  private final DnaInvocationContext targetContext = mock(DnaInvocationContext.class);
  private final FormRecordReadAuthority readAuthority = mock(FormRecordReadAuthority.class);
  private final ApplicationFormReferenceServiceImpl service =
      new ApplicationFormReferenceServiceImpl(
          forms, relations, contexts, dna, records, runtime, readAuthority);
  private PublishedApplicationForm source;
  private PublishedApplicationForm target;
  private FormRelationDefinition relation;

  @BeforeEach
  void setup() {
    when(readAuthority.current(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong()))
        .thenAnswer(call -> call.getArgument(1));
    relation =
        new FormRelationDefinition(
            "1",
            "customer",
            FormRelationCardinality.MANY_TO_ONE,
            new FormRelationField(null, "customer"),
            new FormRelationTarget("customers", "customers", "customer-resource"),
            null,
            new FormReferenceView(
                "name",
                List.of("name"),
                List.of("phone"),
                FormRelationQualificationTest.active(),
                true));
    source =
        form(
            "trades",
            new ApplicationFormDocument(
                "1",
                List.of(field("customer", FormFieldType.UUID)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(relation)));
    target =
        form(
            "customers",
            new ApplicationFormDocument(
                "1",
                List.of(
                    field("name", FormFieldType.STRING),
                    field("phone", FormFieldType.STRING),
                    field("active", FormFieldType.BOOLEAN)),
                List.of(),
                List.of(),
                List.of()));
    when(forms.resolve("trades", "trades-page")).thenReturn(source);
    when(relations.resolve(source, "customer"))
        .thenReturn(new FormRelationTargetResolver.ResolvedRelation(relation, target));
    when(contexts.forForm(source)).thenReturn(sourceContext);
    when(contexts.forRelatedRead(any(), eq(target))).thenReturn(targetContext);
    when(sourceContext.fieldPermissions()).thenReturn(Map.of());
    when(targetContext.fieldPermissions()).thenReturn(Map.of());
  }

  @Test
  void delegatedSourcePolicyCannotAuthorizeTargetLookupOrDrillThrough() {
    when(contexts.forRelatedRead(source, target)).thenReturn(null);
    String id = UUID.randomUUID().toString();
    denied(() -> service.resolve("trades", "trades-page", "customer", List.of(id), RUNTIME));
    denied(() -> service.detail("trades", "trades-page", "customer", id, RUNTIME));
    verifyNoInteractions(dna, records, runtime);
  }

  @Test
  void drillThroughUsesTheExactFrozenEntryAndItsAuthorizedRecordReader() {
    String id = UUID.randomUUID().toString();
    var record =
        new ApplicationFormRecordView(
            id, 12, FormRecordState.SUBMITTED, Map.of("name", "客户日本", "masked", "***"));
    var descriptor = mock(ApplicationFormRuntimeView.class);
    when(records.getDecorated(target, id)).thenReturn(record);
    when(runtime.describe(target, null)).thenReturn(descriptor);

    var result = service.detail("trades", "trades-page", "customer", id.toUpperCase(), RUNTIME);
    assertThat(result.form()).isSameAs(descriptor);
    assertThat(result.record()).isSameAs(record);
    verify(forms).resolve("trades", "trades-page");
    verifyNoMoreInteractions(forms);
    verify(records).getDecorated(target, id);
    verify(runtime).describe(target, null);
    verifyNoInteractions(dna); // No candidate search or fallback to another target resource.
  }

  @Test
  void drillThroughDenialNeverReturnsRuntimeOrFallsBackToAnotherEntry() {
    String id = UUID.randomUUID().toString();
    var denial =
        new ApplicationFormException(ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED, "denied");
    when(records.getDecorated(target, id)).thenThrow(denial);
    assertThatThrownBy(() -> service.detail("trades", "trades-page", "customer", id, RUNTIME))
        .isSameAs(denial);
    verifyNoInteractions(runtime, dna);
  }

  @Test
  void disabledDrillThroughIsRejectedBeforeResolvingTheTarget() {
    var disabled =
        new FormRelationDefinition(
            "1",
            "customer",
            relation.cardinality(),
            relation.sourceField(),
            relation.target(),
            null,
            new FormReferenceView("name", List.of(), List.of(), null, false));
    var restricted =
        form(
            "trades",
            new ApplicationFormDocument(
                "1",
                source.document().fields(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(disabled)));
    when(forms.resolve("trades", "trades-page")).thenReturn(restricted);
    when(contexts.forForm(restricted)).thenReturn(sourceContext);
    denied(
        () ->
            service.detail(
                "trades", "trades-page", "customer", UUID.randomUUID().toString(), RUNTIME));
    verifyNoInteractions(relations, records, runtime, dna);
    when(relations.resolve(restricted, "customer"))
        .thenReturn(new FormRelationTargetResolver.ResolvedRelation(disabled, target));
    when(dna.pageConditionedRecords(eq(targetContext), any()))
        .thenReturn(
            new RecordPageView(
                0,
                50,
                1,
                List.of(
                    new RecordView(UUID.randomUUID().toString(), 1, Map.of("col_name", "客户")))));
    assertThat(service.search("trades", "trades-page", "customer", "", 0, 50, RUNTIME).records())
        .singleElement()
        .satisfies(option -> assertThat(option.canDrillThrough()).isFalse());
  }

  @Test
  void malformedDrillThroughIdentityNeverReachesAnAuthorizedReader() {
    for (String id : new String[] {null, "", "1-1-1-1-1", "../other", "***"}) {
      assertThatThrownBy(() -> service.detail("trades", "trades-page", "customer", id, RUNTIME))
          .isInstanceOfSatisfying(
              ApplicationFormException.class,
              failure ->
                  assertThat(failure.errorCode())
                      .isEqualTo(ApplicationFormErrorCode.RECORD_INVALID));
    }
    verifyNoInteractions(forms, relations, records, runtime, dna);
  }

  @Test
  void searchUsesOnlyFrozenPresentationAndTargetPolicyWithServerPaging() {
    String id = UUID.randomUUID().toString();
    when(dna.pageConditionedRecords(eq(targetContext), any()))
        .thenReturn(
            new RecordPageView(
                1,
                50,
                61,
                List.of(
                    new RecordView(
                        id,
                        7,
                        Map.of(
                            "col_name",
                            "客户日本",
                            "col_phone",
                            "123",
                            "col_secret",
                            "not projected")))));
    var result = service.search("trades", "trades-page", "customer", " 日本 ", 1, 50, RUNTIME);
    assertThat(result.total()).isEqualTo(61);
    assertThat(result.records())
        .singleElement()
        .satisfies(
            option -> {
              assertThat(option.label()).isEqualTo("客户日本");
              assertThat(option.version()).isEqualTo(7);
              assertThat(option.canDrillThrough()).isTrue();
              assertThat(option.values())
                  .containsExactlyInAnyOrderEntriesOf(Map.of("name", "客户日本", "phone", "123"));
            });
    var capture = ArgumentCaptor.forClass(RecordPageQuery.class);
    verify(dna).pageConditionedRecords(eq(targetContext), capture.capture());
    var query = capture.getValue();
    assertThat(query.physicalModelId()).isEqualTo("customers-model");
    assertThat(query.page()).isEqualTo(1);
    assertThat(query.fields()).containsExactly("col_name", "col_phone");
    assertThat(query.filter().field()).isEqualTo("col_name");
    assertThat(query.filter().values()).containsExactly("日本");
    assertThat(query.condition().field()).isEqualTo("col_active");
    assertThat(query.condition().operator()).isEqualTo(RecordValueConditionOperator.EQUALS);
    var proof =
        ArgumentCaptor.forClass(
            org.simplepoint.plugin.dna.runtime.api.record.RecordCurrentTargetsQuery.class);
    verify(readAuthority)
        .current(
            eq(target), eq(targetContext), proof.capture(), org.mockito.ArgumentMatchers.anyLong());
    assertThat(proof.getValue().page()).isEqualTo(query);
  }

  @Test
  void legacyDirectoryOptionsNeverImplyDrillThroughCapability() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var legacy = new FormReferenceOption("record", 1, "客户", Map.of());
    assertThat(mapper.writeValueAsString(legacy)).doesNotContain("canDrillThrough");
    assertThat(
            mapper
                .readValue(mapper.writeValueAsString(legacy), FormReferenceOption.class)
                .canDrillThrough())
        .isFalse();
  }

  @Test
  void hydrationUsesOneBoundedQueryWithoutBrowseFiltersAndPreservesRequestedOrder() {
    String first = UUID.randomUUID().toString();
    String second = UUID.randomUUID().toString();
    String hidden = UUID.randomUUID().toString();
    when(dna.pageConditionedRecords(eq(targetContext), any()))
        .thenReturn(
            new RecordPageView(
                0,
                3,
                2,
                List.of(
                    new RecordView(second, 1, Map.of("col_name", "Second")),
                    new RecordView(first, 2, Map.of("col_name", "First")))));
    var resolved =
        service.resolve(
            "trades", "trades-page", "customer", List.of(first, second, hidden, first), RUNTIME);
    assertThat(resolved).extracting(FormReferenceOption::id).containsExactly(first, second);
    var capture = ArgumentCaptor.forClass(RecordPageQuery.class);
    verify(dna).pageConditionedRecords(eq(targetContext), capture.capture());
    assertThat(capture.getValue().condition()).isNull();
    assertThat(capture.getValue().filter().field()).isEqualTo("id");
    assertThat(capture.getValue().filter().values()).containsExactly(first, second, hidden);
  }

  @Test
  void hiddenOrMaskedSourceNeverLooksUpTheTarget() {
    for (String policy : List.of("HIDDEN", "MASKED")) {
      when(sourceContext.fieldPermissions())
          .thenReturn(
              Map.of(
                  RecordPermissionModel.key("trades-model")
                      + "#"
                      + RecordPermissionModel.field("customer"),
                  policy));
      denied(() -> service.search("trades", "trades-page", "customer", "", 0, 50, RUNTIME));
      denied(
          () ->
              service.detail(
                  "trades", "trades-page", "customer", UUID.randomUUID().toString(), RUNTIME));
    }
    verifyNoInteractions(relations, dna, records, runtime, readAuthority);
  }

  @Test
  void unreadableDisplayIsOmittedButUnreadableLabelCannotBecomeQuerySideChannels() {
    String prefix = RecordPermissionModel.key("customers-model") + "#";
    when(targetContext.fieldPermissions())
        .thenReturn(Map.of(prefix + RecordPermissionModel.field("phone"), "MASKED"));
    when(dna.pageConditionedRecords(eq(targetContext), any()))
        .thenReturn(new RecordPageView(0, 50, 0, List.of()));
    service.search("trades", "trades-page", "customer", "", 0, 50, RUNTIME);
    var capture = ArgumentCaptor.forClass(RecordPageQuery.class);
    verify(dna).pageConditionedRecords(eq(targetContext), capture.capture());
    assertThat(capture.getValue().fields()).containsExactly("col_name");
    clearInvocations(dna);
    when(targetContext.fieldPermissions())
        .thenReturn(Map.of(prefix + RecordPermissionModel.field("name"), "HIDDEN"));
    denied(() -> service.search("trades", "trades-page", "customer", "", 0, 50, RUNTIME));
    verifyNoInteractions(dna);
  }

  @Test
  void rejectedBudgetsCannotReachMetadataOrDataReaders() {
    for (Runnable call :
        List.<Runnable>of(
            () -> service.search("trades", "trades-page", "customer", "", -1, 50, RUNTIME),
            () -> service.search("trades", "trades-page", "customer", "", 0, 101, RUNTIME),
            () ->
                service.search(
                    "trades", "trades-page", "customer", "日".repeat(201), 0, 50, RUNTIME),
            () ->
                service.resolve(
                    "trades", "trades-page", "customer", List.of("not-a-record-id"), RUNTIME),
            () ->
                service.resolve(
                    "trades",
                    "trades-page",
                    "customer",
                    java.util.Collections.nCopies(101, UUID.randomUUID().toString()),
                    RUNTIME))) {
      assertThatThrownBy(call::run)
          .isInstanceOfSatisfying(
              ApplicationFormException.class,
              failure ->
                  assertThat(failure.errorCode())
                      .isEqualTo(ApplicationFormErrorCode.RECORD_INVALID));
    }
    verifyNoInteractions(forms, relations, dna);
  }

  @Test
  void viewMetadataRoundTripsWithoutChangingAbsenceOrQualification() {
    var codec = new FormDocumentCodec(new com.fasterxml.jackson.databind.ObjectMapper());
    var encoded = codec.encode(source.document());
    assertThat(codec.decode(encoded.json())).isEqualTo(source.document());
    assertThat(encoded.json()).doesNotContain("qualification");
    var noFilter = new FormReferenceView("name", List.of(), List.of(), null, false);
    var changed =
        new FormRelationDefinition(
            "1",
            "customer",
            relation.cardinality(),
            relation.sourceField(),
            relation.target(),
            null,
            noFilter);
    var document =
        new ApplicationFormDocument(
            "1",
            source.document().fields(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            List.of(changed));
    assertThat(codec.decode(codec.encode(document).json()).relations().getFirst().referenceView())
        .isEqualTo(noFilter);
  }

  private static void denied(Runnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED));
  }

  @Test
  void stalePageIdentityIsRejectedBeforeAnyTargetRead() {
    var stale = new ApplicationFormRuntimeIdentity("previous-form", 1);
    assertThatThrownBy(() -> service.search("trades", "trades-page", "customer", "", 0, 50, stale))
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(ApplicationFormErrorCode.RUNTIME_VERSION_CONFLICT));
    assertThatThrownBy(
            () ->
                service.detail(
                    "trades", "trades-page", "customer", UUID.randomUUID().toString(), stale))
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(ApplicationFormErrorCode.RUNTIME_VERSION_CONFLICT));
    assertThatThrownBy(
            () ->
                service.resolve(
                    "trades",
                    "trades-page",
                    "customer",
                    List.of(UUID.randomUUID().toString()),
                    new ApplicationFormRuntimeIdentity("trades", 2)))
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(ApplicationFormErrorCode.RUNTIME_VERSION_CONFLICT));
    verifyNoInteractions(relations, contexts, dna);
  }

  @Test
  void detailReferenceChecksTheOwnedModelFieldInsteadOfItsParentsGrant() {
    var childRelation =
        new FormRelationDefinition(
            "1",
            "customer",
            relation.cardinality(),
            new FormRelationField("lines", "customer"),
            relation.target(),
            relation.qualification(),
            relation.referenceView());
    var document =
        new ApplicationFormDocument(
            "1",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(
                new FormDetailDefinition(
                    "lines",
                    "lines",
                    "Lines",
                    List.of(field("customer", FormFieldType.UUID)),
                    List.of())),
            List.of(),
            null,
            List.of(childRelation));
    var childSource =
        new PublishedApplicationForm(
            "tenant-a",
            "trades",
            "trades-page",
            "Trades",
            "trades",
            "Trades",
            1,
            "trades-resource",
            "trades-model",
            1,
            "a".repeat(64),
            document,
            1,
            0,
            List.of(new FormDetailBinding("lines", "line-model", 1, "b".repeat(64))));
    when(forms.resolve("trades", "trades-page")).thenReturn(childSource);
    when(contexts.forForm(childSource)).thenReturn(sourceContext);
    when(sourceContext.fieldPermissions())
        .thenReturn(
            Map.of(
                RecordPermissionModel.key("line-model")
                    + "#"
                    + RecordPermissionModel.field("customer"),
                "HIDDEN"));
    denied(() -> service.search("trades", "trades-page", "customer", "", 0, 50, RUNTIME));
    verifyNoInteractions(relations, dna);
    when(sourceContext.fieldPermissions())
        .thenReturn(
            Map.of(
                RecordPermissionModel.key("trades-model")
                    + "#"
                    + RecordPermissionModel.field("customer"),
                "HIDDEN"));
    when(relations.resolve(childSource, "customer"))
        .thenReturn(new FormRelationTargetResolver.ResolvedRelation(childRelation, target));
    when(dna.pageConditionedRecords(eq(targetContext), any()))
        .thenReturn(new RecordPageView(0, 50, 0, List.of()));
    assertThat(service.search("trades", "trades-page", "customer", "", 0, 50, RUNTIME).total())
        .isZero();
  }

  private static FormFieldDefinition field(String id, FormFieldType type) {
    return new FormFieldDefinition(
        id,
        "col_" + id,
        id,
        id,
        type,
        FormStorageMode.STORED,
        false,
        false,
        null,
        null,
        null,
        null,
        List.of());
  }

  @Test
  void contextIsAppliedToTargetQueryButNeverRequiredForExistingLabelHydration() {
    contextualSource();
    when(dna.pageConditionedRecords(eq(targetContext), any()))
        .thenReturn(new RecordPageView(0, 50, 0, List.of()));
    var input =
        new FormReferenceSearchContext(
            Map.of("region", new FormValue(FormValueKind.STRING, "東京")), null);
    service.searchContext("trades", "trades-page", "customer", "", 0, 50, RUNTIME, input);
    var capture = ArgumentCaptor.forClass(RecordPageQuery.class);
    verify(dna).pageConditionedRecords(eq(targetContext), capture.capture());
    assertThat(capture.getValue().condition().children())
        .extracting(condition -> condition.field())
        .containsExactly("col_phone", "col_active");
    assertThat(
            capture
                .getValue()
                .condition()
                .children()
                .getFirst()
                .values()
                .getFirst()
                .canonicalValue())
        .isEqualTo("東京");
    clearInvocations(dna);
    assertThat(service.search("trades", "trades-page", "customer", "", 0, 50, RUNTIME).total())
        .isZero();
    verifyNoInteractions(dna);
    service.resolve(
        "trades", "trades-page", "customer", List.of(UUID.randomUUID().toString()), RUNTIME);
    capture = ArgumentCaptor.forClass(RecordPageQuery.class);
    verify(dna).pageConditionedRecords(eq(targetContext), capture.capture());
    assertThat(capture.getValue().condition()).isNull();
  }

  @Test
  void hiddenAndMaskedContextFieldsCannotBeUsedForInferenceEvenWithoutInputs() {
    contextualSource();
    for (String policy : List.of("HIDDEN", "MASKED")) {
      when(sourceContext.fieldPermissions())
          .thenReturn(
              Map.of(
                  RecordPermissionModel.key("trades-model")
                      + "#"
                      + RecordPermissionModel.field("region"),
                  policy));
      denied(
          () ->
              service.searchContext("trades", "trades-page", "customer", "", 0, 50, RUNTIME, null));
      when(sourceContext.fieldPermissions()).thenReturn(Map.of());
      when(targetContext.fieldPermissions())
          .thenReturn(
              Map.of(
                  RecordPermissionModel.key("customers-model")
                      + "#"
                      + RecordPermissionModel.field("phone"),
                  policy));
      denied(
          () ->
              service.searchContext("trades", "trades-page", "customer", "", 0, 50, RUNTIME, null));
      when(targetContext.fieldPermissions()).thenReturn(Map.of());
    }
    verifyNoInteractions(dna);
  }

  @Test
  void runtimeContextProjectionSuppressesUnreadableInputsAndKeepsLegacyJsonUnchanged()
      throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    assertThat(
            mapper.writeValueAsString(
                FormReferenceInputProjection.project(source, relation, Map.of())))
        .doesNotContain("contextual", "contextInputs", "searchable");
    contextualSource();
    var projected = new FormRuntimeDocumentCompiler().compile(source, Map.of());
    var reference = projected.fields().getFirst().reference();
    assertThat(reference.contextual()).isTrue();
    assertThat(reference.contextInputs())
        .singleElement()
        .satisfies(
            input -> {
              assertThat(input.fieldId()).isEqualTo("region");
              assertThat(input.type()).isEqualTo(FormFieldType.STRING);
            });
    for (String policy : List.of("HIDDEN", "MASKED")) {
      var restricted =
          new FormRuntimeDocumentCompiler()
              .compile(
                  source,
                  Map.of(
                      RecordPermissionModel.key("trades-model")
                          + "#"
                          + RecordPermissionModel.field("region"),
                      policy));
      var blocked = restricted.fields().getFirst().reference();
      assertThat(blocked.searchable()).isFalse();
      assertThat(blocked.contextInputs()).isEmpty();
      assertThat(mapper.writeValueAsString(blocked))
          .doesNotContain("region", "col_phone", "customers");
    }
  }

  private void contextualSource() {
    var previous = relation.referenceView();
    relation =
        new FormRelationDefinition(
            "1",
            "customer",
            relation.cardinality(),
            relation.sourceField(),
            relation.target(),
            relation.qualification(),
            new FormReferenceView(
                previous.labelFieldId(),
                previous.searchFieldIds(),
                previous.displayFieldIds(),
                previous.candidateFilter(),
                previous.allowDrillThrough(),
                List.of(
                    new FormReferenceContextFilter(
                        "phone",
                        FormConditionOperator.EQUALS,
                        FormReferenceContextFilter.Source.FORM,
                        "region"))));
    source =
        form(
            "trades",
            new ApplicationFormDocument(
                "1",
                List.of(
                    field("customer", FormFieldType.UUID), field("region", FormFieldType.STRING)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(relation)));
    when(forms.resolve("trades", "trades-page")).thenReturn(source);
    when(relations.resolve(source, "customer"))
        .thenReturn(new FormRelationTargetResolver.ResolvedRelation(relation, target));
    when(contexts.forForm(source)).thenReturn(sourceContext);
  }

  private static PublishedApplicationForm form(String id, ApplicationFormDocument document) {
    return new PublishedApplicationForm(
        "tenant-a",
        id,
        id + "-page",
        id,
        id,
        id,
        1,
        id + "-resource",
        id + "-model",
        1,
        "a".repeat(64),
        document);
  }
}
