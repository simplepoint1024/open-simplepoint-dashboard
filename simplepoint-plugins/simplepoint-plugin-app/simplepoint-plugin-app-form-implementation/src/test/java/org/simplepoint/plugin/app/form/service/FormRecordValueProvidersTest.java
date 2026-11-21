package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormDetailDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValidationConstraint;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingConfirmation;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingIntent;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingObservation;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationCardinality;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationField;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.service.DnaOwnedRecordService;

class FormRecordValueProvidersTest {
  private final FormRecordPayloadCompiler compiler = new FormRecordPayloadCompiler();

  @Test
  void rejectsClientManagedValuesButDefersOnlyTheirRequiredRules() {
    var document = document(null, List.of());
    var ordinary = Map.<String, Object>of("name", "客户", "suggestion", "manual");
    assertThat(compiler.compile(document, Map.of(), ordinary, null, FormRecordState.SUBMITTED))
        .containsKeys("name", "suggestion")
        .doesNotContainKey("copied");
    assertThatThrownBy(
            () -> compiler.project(document, Map.of("copied", "forged"), FormRecordState.DRAFT))
        .isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(
            () ->
                compiler.compile(
                    document,
                    Map.of(),
                    Map.of("suggestion", "manual"),
                    null,
                    FormRecordState.SUBMITTED))
        .isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(
            () ->
                compiler.compile(
                    document,
                    Map.of(),
                    Map.of("name", "x".repeat(101), "suggestion", "manual"),
                    null,
                    FormRecordState.SUBMITTED))
        .isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void defaultDeferralBelongsToTheConfirmedRowAndDoesNotMakeTheFieldManaged() {
    var document = document("lines", List.of());
    var intent = intent("lines", 0);
    var first = FormRecordValueProviders.forRow(document, "lines", 0, intent);
    var second = FormRecordValueProviders.forRow(document, "lines", 1, intent);
    assertThat(first.deferred()).containsExactlyInAnyOrder("copied", "suggestion");
    assertThat(first.managed()).containsExactly("copied");
    assertThat(second.deferred()).containsExactly("copied");
    var scalar =
        new ApplicationFormDocument(
            "1", document.details().getFirst().fields(), List.of(), List.of(), List.of());
    assertThat(
            compiler.compile(
                scalar, Map.of(), Map.of("name", "row"), null, FormRecordState.SUBMITTED, first))
        .containsEntry("name", "row");
    assertThatThrownBy(
            () ->
                compiler.compile(
                    scalar,
                    Map.of(),
                    Map.of("name", "row"),
                    null,
                    FormRecordState.SUBMITTED,
                    second))
        .isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void ownedValidationRetainsAttachmentAndRowVersionChecksWhileUsingFullProviderMetadata() {
    var document = document("lines", List.of());
    var form = mock(PublishedApplicationForm.class);
    when(form.document()).thenReturn(document);
    var attachments = mock(FormAttachmentReferenceValidator.class);
    var coordinator =
        new FormDetailRecordCoordinator(mock(DnaOwnedRecordService.class), compiler, attachments);
    var values = Map.<String, Object>of("name", "row");
    var requested = Map.<String, Object>of("lines", List.of(Map.of("values", values)));
    coordinator.validate(
        form, Map.of(), requested, null, FormRecordState.SUBMITTED, intent("lines", 0));
    verify(attachments).validate(document.details().getFirst().fields(), Map.of(), values);
    assertThatThrownBy(
            () -> coordinator.validate(form, Map.of(), requested, null, FormRecordState.SUBMITTED))
        .isInstanceOf(ApplicationFormException.class);
    var forged =
        Map.<String, Object>of(
            "lines", List.of(Map.of("id", "existing", "version", 1, "values", values)));
    assertThatThrownBy(
            () ->
                coordinator.validate(
                    form,
                    Map.of(),
                    forged,
                    FormRecordState.DRAFT,
                    FormRecordState.SUBMITTED,
                    intent("lines", 0)))
        .isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void aggregateDeferralDoesNotDisableOrdinaryRulesOrDetailCount() {
    var range =
        new FormValidationConstraint(
            "range",
            FormValidationConstraint.Kind.RANGE,
            "copied",
            null,
            null,
            null,
            "1",
            "10",
            "range");
    var count =
        new FormValidationConstraint(
            "count",
            FormValidationConstraint.Kind.DETAIL_COUNT,
            null,
            null,
            "lines",
            null,
            "1",
            "40",
            "count");
    var base = document("lines", List.of());
    var amount =
        new FormFieldDefinition(
            "copied",
            "copied",
            "Amount",
            "Amount",
            FormFieldType.DECIMAL,
            FormStorageMode.STORED,
            true,
            false,
            null,
            20,
            2,
            null,
            List.of());
    var document =
        new ApplicationFormDocument(
            "1",
            List.of(amount),
            List.of(),
            List.of(),
            List.of(),
            base.details(),
            List.of(range, count),
            null,
            base.relations());
    FormAggregateValidation.validateDefinition(document);
    assertThatThrownBy(
            () ->
                FormAggregateValidation.validateInputs(
                    document, Map.of(), Map.of("lines", List.of()), Set.of("copied"), Map.of()))
        .isInstanceOf(ApplicationFormException.class);
    FormAggregateValidation.validateInputs(
        document, Map.of(), Map.of("lines", List.of(Map.of())), Set.of("copied"), Map.of());
    assertThatThrownBy(
            () ->
                FormAggregateValidation.validate(
                    document, Map.of("copied", "20"), Map.of("lines", List.of(Map.of()))))
        .isInstanceOf(ApplicationFormException.class);
  }

  private static FormReferenceMappingIntent intent(String detailId, Integer index) {
    return new FormReferenceMappingIntent(
        "1",
        List.of(
            new FormReferenceMappingConfirmation(
                "customer",
                detailId,
                index,
                null,
                new FormReferenceMappingObservation(
                    "a".repeat(64), "11111111-1111-1111-1111-111111111111", 0L),
                List.of("default"))));
  }

  private static ApplicationFormDocument document(
      String detailId, List<FormValidationConstraint> constraints) {
    var reference =
        new FormFieldDefinition(
            "customer",
            "customer",
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
    var fields = List.of(reference, field("name"), field("copied"), field("suggestion"));
    var mappings =
        List.of(
            new FormReferenceMapping(
                "copy",
                "name",
                "copied",
                FormReferenceMapping.Mode.SERVER_SNAPSHOT,
                FormReferenceMapping.Conversion.IDENTITY,
                FormReferenceMapping.NullPolicy.CLEAR),
            new FormReferenceMapping(
                "default",
                "name",
                "suggestion",
                FormReferenceMapping.Mode.EDITABLE_DEFAULT,
                FormReferenceMapping.Conversion.IDENTITY,
                FormReferenceMapping.NullPolicy.CLEAR));
    var relation =
        new FormRelationDefinition(
            "1",
            "customer",
            FormRelationCardinality.MANY_TO_ONE,
            new FormRelationField(detailId, "customer"),
            new FormRelationTarget("app", "target", "page"),
            null,
            null,
            mappings);
    return new ApplicationFormDocument(
        "1",
        detailId == null ? fields : List.of(),
        List.of(),
        List.of(),
        List.of(),
        detailId == null
            ? List.of()
            : List.of(new FormDetailDefinition(detailId, "lines", "Lines", fields, List.of())),
        constraints,
        null,
        List.of(relation));
  }

  private static FormFieldDefinition field(String id) {
    return new FormFieldDefinition(
        id,
        id,
        id,
        id,
        FormFieldType.STRING,
        FormStorageMode.STORED,
        true,
        false,
        100,
        null,
        null,
        null,
        List.of(),
        null,
        new org.simplepoint.plugin.app.form.api.model.FormFieldValidation(null, 100, null, null));
  }
}
