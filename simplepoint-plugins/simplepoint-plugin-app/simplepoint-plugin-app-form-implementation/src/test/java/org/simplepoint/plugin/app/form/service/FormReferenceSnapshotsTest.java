package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceSnapshot;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceView;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceSnapshot;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.service.DnaFormRuntimeService;
import org.simplepoint.plugin.dna.runtime.api.service.DnaOwnedRecordService;
import org.springframework.security.access.AccessDeniedException;

class FormReferenceSnapshotsTest {
  private static final String ROOT = UUID.randomUUID().toString();
  private static final String TARGET = UUID.randomUUID().toString();

  @Test
  void frozenLabelsSurviveAggregateMergeAndJsonWithoutChangingLegacyAbsence() throws Exception {
    var form = source();
    var root = new RecordView(ROOT, 3, Map.of("customer_id", TARGET));
    var rows = new ArrayList<RecordView>();
    var labels = new ArrayList<RecordReferenceSnapshot>();
    labels.add(dnaLabel("customer", ROOT));
    for (int index = 0; index < 40; index++) {
      String id = UUID.randomUUID().toString();
      rows.add(new RecordView(id, 1, Map.of("product_id", TARGET)));
      labels.add(dnaLabel("product", id));
    }
    var aggregate = new OwnedRecordView(root, Map.of("model-lines", rows), labels);
    var coordinator =
        new FormDetailRecordCoordinator(
            mock(DnaOwnedRecordService.class),
            mock(FormRecordPayloadCompiler.class),
            mock(FormAttachmentReferenceValidator.class));
    var merged = coordinator.merge(form, record(Map.of("customer", TARGET)), aggregate);
    assertThat(merged.referenceSnapshots()).hasSize(41);
    assertThat(merged.referenceSnapshots())
        .allSatisfy(label -> assertThat(label.label()).isEqualTo("原名称-日本語😀"));
    var mapper = new ObjectMapper();
    var roundtrip =
        mapper.readValue(mapper.writeValueAsBytes(merged), ApplicationFormRecordView.class);
    assertThat(roundtrip.referenceSnapshots()).isEqualTo(merged.referenceSnapshots());
    assertThat(mapper.readTree(mapper.writeValueAsBytes(roundtrip)))
        .isEqualTo(mapper.readTree(mapper.writeValueAsBytes(merged)));
    assertThat(mapper.writeValueAsString(record(Map.of()))).doesNotContain("referenceSnapshots");
    var codec = new FormSubmissionCodec(mapper);
    assertThat(
            codec.read(codec.write(merged), ApplicationFormRecordView.class).referenceSnapshots())
        .isEqualTo(merged.referenceSnapshots());
    assertThat(
            new FormRecordIdentityLabels(
                    mock(
                        org.simplepoint.plugin.rbac.tenant.api.service.AuditIdentityDirectory
                            .class))
                .decorate("tenant", merged)
                .referenceSnapshots())
        .isEqualTo(merged.referenceSnapshots());
    assertThatThrownBy(
            () ->
                new ApplicationFormRecordView(
                    ROOT,
                    3,
                    FormRecordState.SUBMITTED,
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    List.of(
                        merged.referenceSnapshots().getFirst(),
                        merged.referenceSnapshots().getFirst())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(FormRelationPublicationProjection.rules(form.document(), form.relationBindings()))
        .allSatisfy(rule -> assertThat(rule.snapshotLabelFieldId()).isEqualTo("name"));
  }

  @Test
  void legacyBindingsNeverInferCaptureFromExistingDisplayMetadata() throws Exception {
    var form = source();
    var legacy =
        form.relationBindings().stream()
            .map(
                binding ->
                    new FormRelationBinding(
                        binding.relationId(),
                        binding.target(),
                        binding.targetPageId(),
                        binding.targetResourceCode(),
                        binding.targetPhysicalModelId(),
                        binding.targetFields()))
            .toList();
    var mapper = new ObjectMapper();
    String json = mapper.writeValueAsString(legacy);
    assertThat(json).doesNotContain("snapshotLabelFieldId");
    assertThat(FormRelationPublicationProjection.rules(form.document(), legacy))
        .allSatisfy(rule -> assertThat(rule.snapshotLabelFieldId()).isNull());
    var codec = new FormDocumentCodec(mapper);
    String frozen = codec.encodeRelationBindings(form.document(), legacy);
    assertThat(codec.decodeRelationBindings(form.document(), frozen))
        .containsExactlyElementsOf(legacy);
    assertThat(codec.encodeRelationBindings(form.document(), legacy)).isEqualTo(frozen);
  }

  @Test
  void sourceProjectionOmitsMaskedFieldsAndRejectsWrongTargetIdentity() {
    var form = source();
    for (var values :
        List.of(Map.<String, Object>of(), Map.<String, Object>of("customer", "***"))) {
      assertThat(FormReferenceSnapshots.visible(form, record(values), List.of(label()))).isEmpty();
    }
    assertThatThrownBy(
            () ->
                FormReferenceSnapshots.visible(
                    form,
                    record(Map.of("customer", UUID.randomUUID().toString())),
                    List.of(label())))
        .isInstanceOf(ApplicationFormException.class);
    var captured = dnaLabel("customer", ROOT);
    var tampered =
        new RecordReferenceSnapshot(
            captured.relationId(),
            captured.sourceModelId(),
            captured.sourceRecordId(),
            captured.sourceFieldId(),
            "other-model",
            captured.targetAppId(),
            captured.targetFormId(),
            captured.targetResourceCode(),
            captured.targetRecordId(),
            captured.targetVersion(),
            captured.labelFieldId(),
            captured.label());
    assertThatThrownBy(
            () ->
                FormReferenceSnapshots.capture(
                    form,
                    new OwnedRecordView(
                        new RecordView(ROOT, 3, Map.of()), Map.of(), List.of(tampered)),
                    record(Map.of("customer", TARGET))))
        .isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void historyChecksCurrentVisibilityWithoutFetchingCurrentNames() {
    var f = new Fixture();
    var queries =
        new ArrayList<
            org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceVisibilityQuery>();
    when(f.dna.visibleReferenceRecords(any(), any()))
        .thenAnswer(
            call -> {
              queries.add(call.getArgument(1));
              return List.of(TARGET);
            });
    assertThat(f.read()).containsExactly(label());
    assertThat(queries)
        .singleElement()
        .satisfies(
            query -> {
              assertThat(query.recordIds()).containsExactly(TARGET);
              assertThat(query.labelFieldId()).isEqualTo("name");
              assertThat(query.physicalModelId()).isEqualTo("model-customer");
            });
    when(f.dna.visibleReferenceRecords(any(), any())).thenReturn(List.of());
    assertThat(f.read()).isEmpty();
    when(f.dna.visibleReferenceRecords(any(), any()))
        .thenReturn(List.of(UUID.randomUUID().toString()));
    assertThatThrownBy(f::read).isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void hiddenSourceMissingDelegatedPolicyAndRevokedTargetsNeverQueryDna() {
    var hidden = new Fixture();
    assertThat(
            hidden.access.filter(hidden.form, record(Map.of("customer", "***")), List.of(label())))
        .isEmpty();
    verifyNoInteractions(hidden.relations, hidden.contexts, hidden.dna);
    var delegated = new Fixture();
    when(delegated.contexts.forRelatedRead(any(), any())).thenReturn(null);
    assertThat(delegated.read()).isEmpty();
    verifyNoInteractions(delegated.dna);
    var revoked = new Fixture();
    when(revoked.relations.resolve(any(), any())).thenThrow(new AccessDeniedException("revoked"));
    assertThat(revoked.read()).isEmpty();
    verifyNoInteractions(revoked.dna);
  }

  @Test
  void relatedReadContextNeverBorrowsTheBroadSourcePolicy() {
    var f = new Fixture();
    var factory = new DnaInvocationContextFactory(new FormRequestContext(), f.relations);
    try {
      for (boolean explicit : List.of(false, true)) {
        var auth = new org.simplepoint.core.AuthorizationContext();
        auth.setUserId("reader");
        auth.setAttributes(Map.of("X-Tenant-Id", "tenant"));
        auth.setResources(Set.of("source-resource", "resource-customer"));
        auth.setDataScopeType("ALL");
        if (explicit) {
          auth.setResourceDataScopes(
              Map.of(
                  "resource-customer",
                  new org.simplepoint.core.ResourceDataScope("SELF", Set.of(), false)));
          auth.setResourceFieldPermissions(
              Map.of(
                  "resource-customer", Map.of("dna.runtime.record.model-customer#name", "MASKED")));
        }
        org.simplepoint.core.RequestContextHolder.setContext(
            org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, auth);
        var context = factory.forRelatedRead(f.form, f.target);
        if (explicit) {
          assertThat(context.resourceCode()).isEqualTo("resource-customer");
          assertThat(context.dataScopeType()).isEqualTo("SELF");
          assertThat(context.fieldPermissions())
              .containsEntry("dna.runtime.record.model-customer#name", "MASKED");
        } else {
          assertThat(context).isNull();
        }
      }
    } finally {
      org.simplepoint.core.RequestContextHolder.clearContext(
          org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    }
  }

  @Test
  void submissionHistoryEntryRechecksLabelsWithoutMutatingStoredEvidence() {
    var f = new Fixture();
    var journal = mock(FormSubmissionJournal.class);
    var forms = mock(PublishedApplicationFormResolver.class);
    var actors = mock(FormRequestContext.class);
    when(actors.requireActor()).thenReturn(new FormRequestContext.FormActor("tenant", "reader"));
    var view = mock(org.simplepoint.plugin.app.form.api.model.ApplicationFormSubmissionView.class);
    when(view.applicationId()).thenReturn(f.form.applicationId());
    when(view.pageId()).thenReturn(f.form.pageId());
    var accepted = mock(AcceptedFormSubmission.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    when(accepted.form()).thenReturn(f.form);
    when(accepted.workflow().binding().sceneId()).thenReturn("main");
    var stored =
        new ApplicationFormRecordView(
            ROOT,
            3,
            FormRecordState.SUBMITTED,
            Map.of("customer", TARGET),
            Map.of(),
            Map.of(),
            List.of(label()));
    when(journal.getOwned("submission", "tenant", "reader")).thenReturn(view);
    when(journal.snapshot("submission", "tenant"))
        .thenReturn(new FormSubmissionJournal.Snapshot(view, accepted, stored));
    when(forms.currentCalculationScope(f.form)).thenReturn(f.form);
    when(f.contexts.forForm(f.form))
        .thenReturn(
            new DnaInvocationContext(
                "tenant",
                "reader",
                null,
                "source-resource",
                false,
                Set.of("source-resource", "resource-customer"),
                "ALL",
                Set.of(),
                false,
                List.of(),
                Map.of()));
    var history = mock(FormSubmissionHistory.class);
    when(history.read(any())).thenReturn(stored);
    var service =
        new FormSubmissionSnapshotService(
            mock(FormWorkflowGateway.class),
            journal,
            forms,
            new FormRuntimeDocumentCompiler(),
            f.contexts,
            actors,
            new FormRecordIdentityLabels(
                mock(org.simplepoint.plugin.rbac.tenant.api.service.AuditIdentityDirectory.class)),
            history,
            mock(org.simplepoint.plugin.app.form.api.service.ApplicationFormRecordService.class));
    assertThat(
            service
                .owned(f.form.applicationId(), f.form.pageId(), "submission")
                .record()
                .referenceSnapshots())
        .containsExactly(label());
    when(history.read(any()))
        .thenReturn(
            new ApplicationFormRecordView(
                stored.id(),
                stored.version(),
                stored.state(),
                stored.values(),
                stored.systemValues()));
    assertThat(
            service
                .owned(f.form.applicationId(), f.form.pageId(), "submission")
                .record()
                .referenceSnapshots())
        .isEmpty();
    assertThat(stored.referenceSnapshots()).containsExactly(label());
  }

  private static ApplicationFormRecordView record(Map<String, Object> values) {
    return new ApplicationFormRecordView(ROOT, 3, FormRecordState.SUBMITTED, values);
  }

  private static FormReferenceSnapshot label() {
    return new FormReferenceSnapshot(
        "customer", null, ROOT, "customer", TARGET, 7, "name", "原名称-日本語😀");
  }

  private static RecordReferenceSnapshot dnaLabel(String relation, String sourceRow) {
    return new RecordReferenceSnapshot(
        relation,
        relation.equals("customer") ? "model-source" : "model-lines",
        sourceRow,
        relation,
        "model-" + relation,
        relation.equals("customer") ? "customer-app" : "catalog-app",
        relation,
        "resource-" + relation,
        TARGET,
        7,
        "name",
        "原名称-日本語😀");
  }

  private static PublishedApplicationForm source() {
    var original = FormRelationPublicationProjectionTest.document();
    var relations =
        original.relations().stream()
            .map(
                relation ->
                    new FormRelationDefinition(
                        relation.contractVersion(),
                        relation.relationId(),
                        relation.cardinality(),
                        relation.sourceField(),
                        relation.target(),
                        null,
                        new FormReferenceView("name", List.of(), List.of(), null, true)))
            .toList();
    var document =
        new ApplicationFormDocument(
            "1",
            original.fields(),
            original.indexes(),
            original.scenes(),
            original.rules(),
            original.details(),
            original.constraints(),
            original.presentation(),
            relations);
    return new PublishedApplicationForm(
        "tenant",
        "source-app",
        "page",
        "Source",
        "source",
        "Source",
        1,
        "source-resource",
        "model-source",
        1,
        "a".repeat(64),
        document,
        1,
        1,
        List.of(new FormDetailBinding("lines", "model-lines", 1, "a".repeat(64))),
        null,
        FormRelationPublicationProjectionTest.bindings(document).stream()
            .map(
                binding ->
                    new FormRelationBinding(
                        binding.relationId(),
                        binding.target(),
                        binding.targetPageId(),
                        binding.targetResourceCode(),
                        binding.targetPhysicalModelId(),
                        List.of(
                            new org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceField(
                                "name",
                                org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType
                                    .STRING)),
                        "name"))
            .toList());
  }

  private static final class Fixture {
    final PublishedApplicationForm form = source();
    final FormRelationTargetResolver relations = mock(FormRelationTargetResolver.class);
    final DnaInvocationContextFactory contexts = mock(DnaInvocationContextFactory.class);
    final DnaFormRuntimeService dna = mock(DnaFormRuntimeService.class);
    final PublishedApplicationForm target;
    final FormReferenceSnapshotAccess access =
        new FormReferenceSnapshotAccess(relations, contexts, dna);

    Fixture() {
      var name =
          new FormFieldDefinition(
              "name",
              "current_name",
              "Name",
              "Name",
              FormFieldType.STRING,
              FormStorageMode.STORED,
              false,
              false,
              null,
              null,
              null,
              null,
              List.of());
      var document =
          new ApplicationFormDocument("1", List.of(name), List.of(), List.of(), List.of());
      target =
          new PublishedApplicationForm(
              "tenant",
              "customer-app",
              "page-customer",
              "Customers",
              "customer",
              "Customer",
              2,
              "resource-customer",
              "model-customer",
              2,
              "b".repeat(64),
              document,
              2,
              2,
              List.of(),
              null);
      when(relations.resolve(form, "customer"))
          .thenReturn(
              new FormRelationTargetResolver.ResolvedRelation(
                  form.document().relations().getFirst(), target));
      when(contexts.forRelatedRead(form, target))
          .thenReturn(
              new DnaInvocationContext(
                  "tenant",
                  "reader",
                  null,
                  "resource-customer",
                  false,
                  Set.of("resource-customer"),
                  "ALL",
                  Set.of(),
                  false,
                  List.of(),
                  Map.of()));
    }

    List<FormReferenceSnapshot> read() {
      return access.filter(form, record(Map.of("customer", TARGET)), List.of(label()));
    }
  }
}
