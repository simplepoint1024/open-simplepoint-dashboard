package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceLiveQuery;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationCardinality;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationField;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCurrentTargetsQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.service.DnaFormRuntimeService;

class ApplicationFormReferenceLiveServiceTest {
  private static final String ROW = "11111111-1111-1111-1111-111111111111";
  private static final ApplicationFormRuntimeIdentity RUNTIME =
      new ApplicationFormRuntimeIdentity("form", 2);

  @Test
  void readOnlyViewerGetsCurrentExactValuesThroughOriginalTargetPermissions() {
    var f = new Fixture();
    when(f.sourceScope.fieldPermissions())
        .thenReturn(
            Map.of(
                RecordPermissionModel.key("source-model")
                    + "#"
                    + RecordPermissionModel.field("amount"),
                "VISIBLE"));
    var first = f.read(ROW);
    assertThat(first.values().get("amount").value()).isEqualTo("9007199254740993.12");
    assertThat(first.values().get("name").value()).isEqualTo("客户・日本");
    assertThat(first.values().get("amount").kind()).isEqualTo(FormValueKind.STRING);
    var lookup = ArgumentCaptor.forClass(RecordPageQuery.class);
    verify(f.dna).pageRecords(eq(f.expanded), lookup.capture());
    assertThat(lookup.getValue().fields()).containsExactly("stored_balance", "stored_name");
    var proof = ArgumentCaptor.forClass(RecordCurrentTargetsQuery.class);
    verify(f.reads).current(eq(f.target), eq(f.targetScope), proof.capture(), anyLong());
    assertThat(proof.getValue().page()).isEqualTo(lookup.getValue());
    verify(f.forms, never()).requireAction(any(), any());
    when(f.dna.pageRecords(any(), any()))
        .thenReturn(page(ROW, 8, Map.of("stored_balance", "1.25", "stored_name", "updated")));
    var second = f.read(ROW);
    assertThat(second.targetVersion()).isEqualTo(8);
    assertThat(second.values().get("amount").value()).isEqualTo("1.25");
  }

  @Test
  void clearingAndHiddenLocalOutputsDoNotReadTheTarget() {
    var f = new Fixture();
    assertThat(f.read(null).values().values())
        .allSatisfy(value -> assertThat(value.kind()).isEqualTo(FormValueKind.NULL));
    verifyNoInteractions(f.relations, f.reads, f.dna);
    var hidden = new Fixture();
    when(hidden.sourceScope.fieldPermissions())
        .thenReturn(
            Map.of(
                RecordPermissionModel.key("source-model")
                        + "#"
                        + RecordPermissionModel.field("amount"),
                    "HIDDEN",
                RecordPermissionModel.key("source-model")
                        + "#"
                        + RecordPermissionModel.field("name"),
                    "MASKED"));
    assertThat(hidden.read(ROW).values()).isEmpty();
    verifyNoInteractions(hidden.relations, hidden.reads, hidden.dna);
  }

  @Test
  void staleRuntimeAndMaskedReferenceRejectBeforeAnyTargetRead() {
    var f = new Fixture();
    assertThatThrownBy(
            () ->
                f.service.read(
                    "app",
                    "page",
                    "customer",
                    new FormReferenceLiveQuery(new ApplicationFormRuntimeIdentity("form", 1), ROW)))
        .isInstanceOf(ApplicationFormException.class);
    when(f.sourceScope.fieldPermissions())
        .thenReturn(
            Map.of(RecordPermissionModel.key("source-model") + "#stored_reference", "MASKED"));
    assertThatThrownBy(() -> f.read(ROW)).isInstanceOf(ApplicationFormException.class);
    verifyNoInteractions(f.relations, f.reads, f.dna);
  }

  @Test
  void targetPolicyAndMissingProvenanceNeverFallBackToPreviousValues() {
    var f = new Fixture();
    when(f.targetScope.fieldPermissions())
        .thenReturn(
            Map.of(RecordPermissionModel.key("target-model") + "#stored_balance", "HIDDEN"));
    when(f.dna.pageRecords(any(), any())).thenReturn(page(ROW, 7, Map.of()));
    assertThat(f.read(ROW).values()).isEmpty();
    var lookup = ArgumentCaptor.forClass(RecordPageQuery.class);
    verify(f.dna).pageRecords(any(), lookup.capture());
    assertThat(lookup.getValue().fields()).containsExactly("stored_name");
    var missing = new Fixture();
    when(missing.contexts.forRelatedRead(missing.form, missing.target)).thenReturn(null);
    assertThat(missing.read(ROW).targetVersion()).isNull();
    verifyNoInteractions(missing.reads, missing.dna);
    var gone = new Fixture();
    when(gone.dna.pageRecords(any(), any()))
        .thenThrow(new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_NOT_FOUND, "absent"));
    assertThat(gone.read(ROW).values()).isEmpty();
  }

  @Test
  void unavailableDatabaseAndLossyConversionAreErrorsRatherThanEmptySuccess() {
    var f = new Fixture();
    when(f.dna.pageRecords(any(), any()))
        .thenThrow(new DnaRuntimeException(DnaRuntimeErrorCode.PERSISTENCE_FAILED, "unavailable"));
    assertThatThrownBy(() -> f.read(ROW)).isInstanceOf(ApplicationFormException.class);
    var conversion = new Fixture();
    when(conversion.dna.pageRecords(any(), any()))
        .thenReturn(
            page(ROW, 8, Map.of("stored_balance", "secret-invalid-number", "stored_name", "name")));
    assertThatThrownBy(() -> conversion.read(ROW))
        .isInstanceOf(ApplicationFormException.class)
        .hasMessageNotContaining("secret-invalid-number");
  }

  @Test
  void batchesSelectionsOnceAndPreservesOrderWithoutRevealingMissingRows() {
    var f = new Fixture();
    String second = "22222222-2222-2222-2222-222222222222";
    String missing = "33333333-3333-3333-3333-333333333333";
    when(f.dna.pageRecords(any(), any()))
        .thenReturn(
            new org.simplepoint.plugin.dna.runtime.api.record.RecordPageView(
                0,
                3,
                2,
                List.of(
                    new RecordView(
                        second, 3, Map.of("stored_balance", "2.00", "stored_name", "second")),
                    new RecordView(
                        ROW, 4, Map.of("stored_balance", "1.00", "stored_name", "first")))));
    var result =
        f.service.readBatch(
            "app",
            "page",
            "customer",
            new org.simplepoint.plugin.app.form.api.model.relation.FormReferenceLiveBatchQuery(
                RUNTIME, List.of(ROW, missing, second)));
    assertThat(result.stream().map(value -> value.targetRecordId()).toList())
        .containsExactly(ROW, missing, second);
    assertThat(result.get(1).values()).isEmpty();
    assertThat(result.get(1).targetVersion()).isNull();
    assertThat(result.getLast().values().get("name").value()).isEqualTo("second");
    var query = ArgumentCaptor.forClass(RecordPageQuery.class);
    verify(f.dna).pageRecords(eq(f.expanded), query.capture());
    assertThat(query.getValue().size()).isEqualTo(3);
    assertThat(query.getValue().filter().values()).containsExactly(ROW, missing, second);
    var empty = new Fixture();
    assertThat(
            empty.service.readBatch(
                "app",
                "page",
                "customer",
                new org.simplepoint.plugin.app.form.api.model.relation.FormReferenceLiveBatchQuery(
                    RUNTIME, List.of())))
        .isEmpty();
    verifyNoInteractions(empty.relations, empty.reads, empty.dna);
  }

  private static org.simplepoint.plugin.dna.runtime.api.record.RecordPageView page(
      String id, long version, Map<String, Object> values) {
    return new org.simplepoint.plugin.dna.runtime.api.record.RecordPageView(
        0, 1, 1, List.of(new RecordView(id, version, values)));
  }

  private static final class Fixture {
    final PublishedApplicationFormResolver forms = mock(PublishedApplicationFormResolver.class);
    final FormRelationTargetResolver relations = mock(FormRelationTargetResolver.class);
    final DnaInvocationContextFactory contexts = mock(DnaInvocationContextFactory.class);
    final FormRecordReadAuthority reads = mock(FormRecordReadAuthority.class);
    final DnaFormRuntimeService dna = mock(DnaFormRuntimeService.class);
    final PublishedApplicationForm form = mock(PublishedApplicationForm.class);
    final PublishedApplicationForm target = mock(PublishedApplicationForm.class);
    final DnaInvocationContext sourceScope = mock(DnaInvocationContext.class);
    final DnaInvocationContext targetScope = mock(DnaInvocationContext.class);
    final DnaInvocationContext expanded = mock(DnaInvocationContext.class);
    final ApplicationFormReferenceLiveServiceImpl service =
        new ApplicationFormReferenceLiveServiceImpl(forms, relations, contexts, reads, dna);

    Fixture() {
      var relation =
          new FormRelationDefinition(
              "1",
              "customer",
              FormRelationCardinality.MANY_TO_ONE,
              new FormRelationField(null, "reference"),
              new FormRelationTarget("app", "target", "page"),
              null,
              null,
              List.of(
                  mapping("balance", "amount", FormReferenceMapping.Conversion.STRING),
                  mapping("name", "name", FormReferenceMapping.Conversion.IDENTITY)));
      when(forms.resolve("app", "page")).thenReturn(form);
      when(form.formId()).thenReturn("form");
      when(form.formVersion()).thenReturn(2);
      when(form.physicalModelId()).thenReturn("source-model");
      when(form.document())
          .thenReturn(
              new ApplicationFormDocument(
                  "1",
                  List.of(
                      field("reference", false, FormStorageMode.REFERENCE),
                      field("amount", false, FormStorageMode.TRANSIENT),
                      field("name", false, FormStorageMode.TRANSIENT)),
                  List.of(),
                  List.of(),
                  List.of(),
                  List.of(),
                  null,
                  null,
                  List.of(relation)));
      when(target.document())
          .thenReturn(
              new ApplicationFormDocument(
                  "1",
                  List.of(field("balance", true), field("name", false)),
                  List.of(),
                  List.of(),
                  List.of()));
      when(target.physicalModelId()).thenReturn("target-model");
      when(target.physicalModelVersion()).thenReturn(1);
      when(target.storageChecksum()).thenReturn("a".repeat(64));
      when(contexts.forForm(form)).thenReturn(sourceScope);
      when(contexts.forRelatedRead(form, target)).thenReturn(targetScope);
      when(relations.resolve(form, "customer"))
          .thenReturn(new FormRelationTargetResolver.ResolvedRelation(relation, target));
      when(reads.current(eq(target), eq(targetScope), any(), anyLong())).thenReturn(expanded);
      when(dna.pageRecords(any(), any()))
          .thenReturn(
              page(
                  ROW, 7, Map.of("stored_balance", "9007199254740993.12", "stored_name", "客户・日本")));
    }

    org.simplepoint.plugin.app.form.api.model.relation.FormReferenceLiveView read(String id) {
      return service.read("app", "page", "customer", new FormReferenceLiveQuery(RUNTIME, id));
    }
  }

  private static FormReferenceMapping mapping(
      String source, String destination, FormReferenceMapping.Conversion conversion) {
    return new FormReferenceMapping(
        "live-" + destination,
        source,
        destination,
        FormReferenceMapping.Mode.LIVE,
        conversion,
        FormReferenceMapping.NullPolicy.CLEAR);
  }

  private static FormFieldDefinition field(String id, boolean decimal) {
    return field(id, decimal, FormStorageMode.STORED);
  }

  private static FormFieldDefinition field(String id, boolean decimal, FormStorageMode mode) {
    return new FormFieldDefinition(
        id,
        mode == FormStorageMode.TRANSIENT ? null : "stored_" + id,
        id,
        mode == FormStorageMode.TRANSIENT ? null : id,
        decimal ? FormFieldType.DECIMAL : FormFieldType.STRING,
        mode,
        false,
        false,
        decimal ? null : 100,
        decimal ? 20 : null,
        decimal ? 2 : null,
        null,
        List.of());
  }
}
