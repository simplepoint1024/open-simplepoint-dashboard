package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.FormDetailDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldAccess;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormFieldWritePolicy;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingQuery;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingView;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationCardinality;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationField;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingObservation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingView;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;
import org.simplepoint.plugin.dna.runtime.api.service.DnaOwnedRecordService;
import org.springframework.security.access.AccessDeniedException;

class ApplicationFormReferenceMappingServiceTest {
  private static final String TARGET = "11111111-1111-1111-1111-111111111111";
  private static final String SOURCE = "22222222-2222-2222-2222-222222222222";
  private static final ApplicationFormRuntimeIdentity RUNTIME =
      new ApplicationFormRuntimeIdentity("form", 2);

  @Test
  void derivesStorageAndAuthorityAndPreservesCanonicalValues() {
    var f = new Fixture(null, false);
    var result = f.observe(f.query(null, null, TARGET));
    assertThat(result.snapshots().get("amount").value()).isEqualTo("9007199254740993.12");
    assertThat(result.defaults().get("memo").value()).isEqualTo("客户・日本");
    verify(f.forms).requireAction(f.form, "records.create");
    var query = ArgumentCaptor.forClass(RecordReferenceMappingQuery.class);
    verify(f.dna).observeMapping(eq(f.authority), query.capture());
    assertThat(query.getValue().physicalModelId()).isEqualTo("physical-root");
    assertThat(query.getValue().schemaReference().version()).isEqualTo(3);
    assertThat(query.getValue().writePolicyVersion()).isEqualTo(7);
    assertThat(query.getValue().applyDefaultMappingIds()).containsExactly("default-memo");
    verifyNoInteractions(f.records);
    verifyNoMoreInteractions(f.dna);
  }

  @Test
  void sourceActionStaleRuntimeAndUnknownDefaultsRejectBeforeTargetReads() {
    var denied = new Fixture(null, false);
    doThrow(new AccessDeniedException("denied"))
        .when(denied.forms)
        .requireAction(denied.form, "records.create");
    assertThatThrownBy(() -> denied.observe(denied.query(null, null, TARGET)))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(denied.dna, denied.relations, denied.records);
    var stale = new Fixture(null, false);
    assertThatThrownBy(
            () ->
                stale.observe(
                    new FormReferenceMappingQuery(
                        new ApplicationFormRuntimeIdentity("form", 1),
                        null,
                        null,
                        TARGET,
                        List.of())))
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            e ->
                assertThat(e.errorCode())
                    .isEqualTo(ApplicationFormErrorCode.RUNTIME_VERSION_CONFLICT));
    assertThatThrownBy(
            () ->
                stale.observe(
                    new FormReferenceMappingQuery(RUNTIME, null, null, TARGET, List.of("unknown"))))
        .isInstanceOf(ApplicationFormException.class);
    verifyNoInteractions(stale.dna, stale.relations, stale.records);
  }

  @Test
  void verifiesCurrentSourceVersionAndDetailOperationPolicyBeforeAnyTargetRead() {
    var stale = new Fixture("lines", false);
    when(stale.records.get(stale.form, SOURCE))
        .thenReturn(new ApplicationFormRecordView(SOURCE, 2, FormRecordState.DRAFT, Map.of()));
    assertThatThrownBy(() -> stale.observe(stale.query(SOURCE, 1L, TARGET)))
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ApplicationFormErrorCode.RECORD_CONFLICT));
    verify(stale.forms).requireAction(stale.form, "records.edit");
    verifyNoInteractions(stale.dna, stale.relations);
    var hidden = new Fixture("lines", true);
    assertThatThrownBy(() -> hidden.observe(hidden.query(null, null, TARGET)))
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            e ->
                assertThat(e.errorCode())
                    .isEqualTo(ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED));
    verifyNoInteractions(hidden.dna, hidden.relations);
    var allowed = new Fixture("lines", false);
    when(allowed.records.get(allowed.form, SOURCE))
        .thenReturn(new ApplicationFormRecordView(SOURCE, 1, FormRecordState.DRAFT, Map.of()));
    assertThat(allowed.observe(allowed.query(SOURCE, 1L, TARGET)).snapshots())
        .containsKey("amount");
  }

  @Test
  void explicitClearNeedsNoAvailableTargetAndInconsistentResultsFailClosed() {
    var f = new Fixture(null, false);
    when(f.relations.availableForWrite(f.form)).thenReturn(List.of());
    when(f.contexts.forRelations(f.form, List.of())).thenReturn(f.authority);
    when(f.dna.observeMapping(any(), any()))
        .thenReturn(
            new RecordReferenceMappingView(
                new RecordReferenceMappingObservation("b".repeat(64), null, null),
                Map.of("amount", LogicalValue.nullValue()),
                Map.of("memo", LogicalValue.nullValue())));
    assertThat(f.observe(f.query(null, null, null)).snapshots().get("amount").kind())
        .isEqualTo(FormValueKind.NULL);
    when(f.dna.observeMapping(any(), any()))
        .thenReturn(
            new RecordReferenceMappingView(
                new RecordReferenceMappingObservation("b".repeat(64), TARGET, 1L),
                Map.of("foreign-field", new LogicalValue(LogicalValueKind.STRING, "hidden")),
                Map.of()));
    assertThatThrownBy(() -> f.observe(f.query(null, null, TARGET)))
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            e ->
                assertThat(e.errorCode())
                    .isEqualTo(ApplicationFormErrorCode.RECORD_OPERATION_FAILED));
  }

  @Test
  void newDetailUsesCreatePolicyAndExistingDetailRequiresOwnedRow() {
    String row = "33333333-3333-3333-3333-333333333333";
    var fresh = new Fixture("lines", false, true);
    when(fresh.records.get(fresh.form, SOURCE))
        .thenReturn(
            new ApplicationFormRecordView(
                SOURCE,
                1,
                FormRecordState.DRAFT,
                Map.of("lines", List.of(Map.of("id", row, "values", Map.of())))));
    assertThat(
            fresh
                .observe(
                    new FormReferenceMappingQuery(
                        RUNTIME, SOURCE, 1L, TARGET, List.of("default-memo"), null, true))
                .snapshots())
        .containsKey("amount");
    var saved = new Fixture("lines", false, true);
    when(saved.records.get(saved.form, SOURCE))
        .thenReturn(
            new ApplicationFormRecordView(
                SOURCE,
                1,
                FormRecordState.DRAFT,
                Map.of("lines", List.of(Map.of("id", row, "values", Map.of())))));
    assertThatThrownBy(
            () ->
                saved.observe(
                    new FormReferenceMappingQuery(
                        RUNTIME, SOURCE, 1L, TARGET, List.of("default-memo"), row, null)))
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED));
    verifyNoInteractions(saved.dna, saved.mappingAuthority);
    var foreign = new Fixture("lines", false);
    when(foreign.records.get(foreign.form, SOURCE))
        .thenReturn(new ApplicationFormRecordView(SOURCE, 1, FormRecordState.DRAFT, Map.of()));
    assertThatThrownBy(
            () ->
                foreign.observe(
                    new FormReferenceMappingQuery(
                        RUNTIME, SOURCE, 1L, TARGET, List.of("default-memo"), row, null)))
        .isInstanceOf(ApplicationFormException.class);
    verifyNoInteractions(foreign.dna, foreign.mappingAuthority);
    var parent = new Fixture(null, false);
    assertThatThrownBy(
            () ->
                parent.observe(
                    new FormReferenceMappingQuery(
                        RUNTIME, null, null, TARGET, List.of("default-memo"), null, true)))
        .isInstanceOf(ApplicationFormException.class);
    verifyNoInteractions(parent.records, parent.dna, parent.mappingAuthority);
  }

  @Test
  void onlyPreserveNullDefaultsMayBeAbsentFromTheObservation() {
    var absent =
        new RecordReferenceMappingView(
            new RecordReferenceMappingObservation("b".repeat(64), TARGET, 4L),
            Map.of("amount", new LogicalValue(LogicalValueKind.DECIMAL, "1.00")),
            Map.of());
    var preserve = new Fixture(null, false, false, FormReferenceMapping.NullPolicy.PRESERVE);
    when(preserve.dna.observeMapping(any(), any())).thenReturn(absent);
    assertThat(preserve.observe(preserve.query(null, null, TARGET)).defaults()).isEmpty();
    var clear = new Fixture(null, false);
    when(clear.dna.observeMapping(any(), any())).thenReturn(absent);
    assertThatThrownBy(() -> clear.observe(clear.query(null, null, TARGET)))
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(ApplicationFormErrorCode.RECORD_OPERATION_FAILED));
  }

  private static final class Fixture {
    final PublishedApplicationFormResolver forms = mock(PublishedApplicationFormResolver.class);
    final FormRelationTargetResolver relations = mock(FormRelationTargetResolver.class);
    final ApplicationFormRecordServiceImpl records = mock(ApplicationFormRecordServiceImpl.class);
    final DnaInvocationContextFactory contexts = mock(DnaInvocationContextFactory.class);
    final DnaOwnedRecordService dna = mock(DnaOwnedRecordService.class);
    final PublishedApplicationForm form = mock(PublishedApplicationForm.class);
    final PublishedApplicationForm target = mock(PublishedApplicationForm.class);
    final DnaInvocationContext authority = mock(DnaInvocationContext.class);
    final FormReferenceMappingAuthority mappingAuthority =
        mock(FormReferenceMappingAuthority.class);
    final ApplicationFormReferenceMappingServiceImpl service =
        new ApplicationFormReferenceMappingServiceImpl(forms, records, dna, mappingAuthority);

    Fixture(String detailId, boolean hidden) {
      this(detailId, hidden, false);
    }

    Fixture(String detailId, boolean hidden, boolean createOnly) {
      this(detailId, hidden, createOnly, FormReferenceMapping.NullPolicy.CLEAR);
    }

    Fixture(
        String detailId,
        boolean hidden,
        boolean createOnly,
        FormReferenceMapping.NullPolicy nullPolicy) {
      var reference =
          field(
              "customer",
              FormStorageMode.REFERENCE,
              hidden,
              createOnly ? FormFieldWritePolicy.CREATE_ONLY : null);
      var fields =
          List.of(
              reference,
              field("amount", FormStorageMode.STORED, false),
              field("memo", FormStorageMode.STORED, false));
      var relation =
          new FormRelationDefinition(
              "1",
              "customer-relation",
              FormRelationCardinality.MANY_TO_ONE,
              new FormRelationField(detailId, "customer"),
              new FormRelationTarget("app", "target", "target-page"),
              null,
              null,
              List.of(
                  new FormReferenceMapping(
                      "snapshot-amount",
                      "balance",
                      "amount",
                      FormReferenceMapping.Mode.SERVER_SNAPSHOT,
                      FormReferenceMapping.Conversion.IDENTITY,
                      FormReferenceMapping.NullPolicy.CLEAR),
                  new FormReferenceMapping(
                      "default-memo",
                      "name",
                      "memo",
                      FormReferenceMapping.Mode.EDITABLE_DEFAULT,
                      FormReferenceMapping.Conversion.IDENTITY,
                      nullPolicy)));
      var document =
          new ApplicationFormDocument(
              "1",
              detailId == null ? fields : List.of(),
              List.of(),
              List.of(),
              List.of(),
              detailId == null
                  ? List.of()
                  : List.of(
                      new FormDetailDefinition(detailId, "lines", "Lines", fields, List.of())),
              null,
              null,
              List.of(relation));
      when(forms.resolve("app", "page")).thenReturn(form);
      when(mappingAuthority.observe(eq(form), any(), any(), any())).thenReturn(authority);
      when(form.formId()).thenReturn("form");
      when(form.formVersion()).thenReturn(2);
      when(form.document()).thenReturn(document);
      when(form.physicalModelId()).thenReturn("physical-root");
      when(form.physicalModelVersion()).thenReturn(3);
      when(form.storageChecksum()).thenReturn("a".repeat(64));
      when(form.writeAdmissionVersion()).thenReturn(7L);
      when(relations.availableForWrite(form)).thenReturn(List.of(target));
      when(contexts.forRelations(form, List.of(target))).thenReturn(authority);
      when(dna.observeMapping(any(), any()))
          .thenReturn(
              new RecordReferenceMappingView(
                  new RecordReferenceMappingObservation("b".repeat(64), TARGET, 4L),
                  Map.of(
                      "amount", new LogicalValue(LogicalValueKind.DECIMAL, "9007199254740993.12")),
                  Map.of("memo", new LogicalValue(LogicalValueKind.STRING, "客户・日本"))));
    }

    FormReferenceMappingQuery query(String recordId, Long version, String targetId) {
      return new FormReferenceMappingQuery(
          RUNTIME, recordId, version, targetId, List.of("default-memo"));
    }

    FormReferenceMappingView observe(FormReferenceMappingQuery query) {
      return service.observe("app", "page", "customer-relation", query);
    }
  }

  private static FormFieldDefinition field(String id, FormStorageMode mode, boolean hidden) {
    return field(id, mode, hidden, null);
  }

  private static FormFieldDefinition field(
      String id, FormStorageMode mode, boolean hidden, FormFieldWritePolicy policy) {
    return new FormFieldDefinition(
        id,
        "stored_" + id,
        id,
        id,
        FormFieldType.STRING,
        mode,
        false,
        false,
        100,
        null,
        null,
        null,
        List.of(),
        policy,
        null,
        hidden
            ? new FormFieldAccess(
                false, FormFieldAccess.CreateMode.HIDDEN, FormFieldAccess.EditMode.HIDDEN)
            : null);
  }
}
