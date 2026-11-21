package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationCardinality;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationField;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCurrentTargetsQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;

class FormReferenceMappingAuthorityTest {
  private static final String ROW = "11111111-1111-1111-1111-111111111111";

  @Test
  void discoversOnlySelectedInputsAndResolvesTheirOriginalEntrances() {
    var f = new Fixture();
    assertThat(f.authority.observe(f.form, f.relation, ROW, List.of())).isSameAs(f.finalScope);
    var query = ArgumentCaptor.forClass(RecordCurrentTargetsQuery.class);
    verify(f.reads).originals(eq(f.target), eq(f.targetScope), query.capture(), anyLong());
    assertThat(query.getValue().page().fields()).containsExactly("stored_value");
    assertThat(query.getValue().page().filter().field()).isEqualTo("id");
    assertThat(query.getValue().page().filter().values()).containsExactly(ROW);
    assertThat(query.getValue().page().size()).isEqualTo(100);
    verify(f.contexts).forRelations(f.form, Set.of(f.target, f.original));
  }

  @Test
  void selectedDefaultsParticipateButExplicitClearDoesNotReadTargets() {
    var f = new Fixture();
    f.authority.observe(f.form, f.relation, ROW, List.of("default"));
    var query = ArgumentCaptor.forClass(RecordCurrentTargetsQuery.class);
    verify(f.reads).originals(eq(f.target), eq(f.targetScope), query.capture(), anyLong());
    assertThat(query.getValue().page().fields()).containsExactly("stored_value", "stored_name");
    var cleared = new Fixture();
    cleared.authority.observe(cleared.form, cleared.relation, null, List.of("default"));
    verifyNoInteractions(cleared.reads);
    verify(cleared.contexts).forRelations(cleared.form, Set.of(cleared.target));
  }

  @Test
  void maskedSourceAndUnwritableDestinationRejectBeforeTargetDiscovery() {
    for (String field : List.of("reference", "copy")) {
      var f = new Fixture();
      when(f.sourceScope.fieldPermissions())
          .thenReturn(
              Map.of(RecordPermissionModel.key("source-model") + "#stored_" + field, "MASKED"));
      assertThatThrownBy(() -> f.authority.observe(f.form, f.relation, ROW, List.of()))
          .isInstanceOf(ApplicationFormException.class);
      verifyNoInteractions(f.relations, f.reads);
    }
  }

  @Test
  void missingTargetScopeCannotBorrowTheSourcePolicy() {
    var f = new Fixture();
    when(f.contexts.forRelatedRead(f.form, f.target)).thenReturn(null);
    assertThatThrownBy(() -> f.authority.observe(f.form, f.relation, ROW, List.of()))
        .isInstanceOf(ApplicationFormException.class);
    verifyNoInteractions(f.reads);
  }

  private static final class Fixture {
    final DnaInvocationContextFactory contexts = mock(DnaInvocationContextFactory.class);
    final FormRelationTargetResolver relations = mock(FormRelationTargetResolver.class);
    final FormRecordReadAuthority reads = mock(FormRecordReadAuthority.class);
    final PublishedApplicationForm form = mock(PublishedApplicationForm.class);
    final PublishedApplicationForm target = mock(PublishedApplicationForm.class);
    final PublishedApplicationForm original = mock(PublishedApplicationForm.class);
    final DnaInvocationContext sourceScope = mock(DnaInvocationContext.class);
    final DnaInvocationContext targetScope = mock(DnaInvocationContext.class);
    final DnaInvocationContext finalScope = mock(DnaInvocationContext.class);
    final FormRelationDefinition relation =
        new FormRelationDefinition(
            "1",
            "reference",
            FormRelationCardinality.MANY_TO_ONE,
            new FormRelationField(null, "reference"),
            new FormRelationTarget("app", "target", "page"),
            null,
            null,
            List.of(
                new FormReferenceMapping(
                    "copy",
                    "value",
                    "copy",
                    FormReferenceMapping.Mode.SERVER_SNAPSHOT,
                    FormReferenceMapping.Conversion.IDENTITY,
                    FormReferenceMapping.NullPolicy.CLEAR),
                new FormReferenceMapping(
                    "default",
                    "name",
                    "default",
                    FormReferenceMapping.Mode.EDITABLE_DEFAULT,
                    FormReferenceMapping.Conversion.IDENTITY,
                    FormReferenceMapping.NullPolicy.CLEAR)));
    final FormReferenceMappingAuthority authority =
        new FormReferenceMappingAuthority(contexts, relations, reads);

    Fixture() {
      when(form.document())
          .thenReturn(
              new ApplicationFormDocument(
                  "1",
                  List.of(field("reference"), field("copy"), field("default")),
                  List.of(),
                  List.of(),
                  List.of(),
                  List.of(),
                  null,
                  null,
                  List.of(relation)));
      when(form.physicalModelId()).thenReturn("source-model");
      when(target.document())
          .thenReturn(
              new ApplicationFormDocument(
                  "1", List.of(field("value"), field("name")), List.of(), List.of(), List.of()));
      when(target.physicalModelId()).thenReturn("target-model");
      when(target.physicalModelVersion()).thenReturn(1);
      when(target.storageChecksum()).thenReturn("a".repeat(64));
      when(contexts.forForm(form)).thenReturn(sourceScope);
      when(sourceScope.fieldPermissions()).thenReturn(Map.of());
      when(contexts.forRelatedRead(form, target)).thenReturn(targetScope);
      when(contexts.forRelations(eq(form), any())).thenReturn(finalScope);
      when(relations.availableForWrite(form)).thenReturn(List.of(target));
      when(relations.resolve(form, "reference"))
          .thenReturn(new FormRelationTargetResolver.ResolvedRelation(relation, target));
      when(reads.originals(eq(target), eq(targetScope), any(), anyLong()))
          .thenReturn(List.of(original));
    }
  }

  private static FormFieldDefinition field(String id) {
    return new FormFieldDefinition(
        id,
        "stored_" + id,
        id,
        id,
        FormFieldType.STRING,
        FormStorageMode.STORED,
        false,
        false,
        100,
        null,
        null,
        null,
        List.of());
  }
}
