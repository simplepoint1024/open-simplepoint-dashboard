package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;
import org.simplepoint.plugin.app.form.service.FormRecordInvariantService.Plan;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.record.RecordLookupQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.service.DnaFormRuntimeService;

class FormRecordInvariantValidatorTest {
  @Test
  void createsUseCurrentStorageDefaultsAndCannotUseHistoricalDefaultsForExplicitNulls() {
    var governing = document(List.of(field(0, false, null)));
    var historical =
        document(List.of(field(0, true, new FormValue(FormValueKind.STRING, "old-default"))));
    var plan = new Plan(governing, 2, "b".repeat(64), List.of(historical));
    assertThat(plan.createDefaults()).containsEntry("field-0", null);
    assertThatThrownBy(
            () ->
                plan.validate(
                    new FormRecordPayloadCompiler(),
                    plan.createDefaults(),
                    Map.of(),
                    null,
                    FormRecordState.SUBMITTED))
        .isInstanceOf(ApplicationFormException.class);
    plan.validate(
        new FormRecordPayloadCompiler(),
        plan.createDefaults(),
        Map.of("field-0", "new"),
        null,
        FormRecordState.SUBMITTED);
    plan.validate(
        new FormRecordPayloadCompiler(),
        plan.createDefaults(),
        Map.of(),
        null,
        FormRecordState.DRAFT);
  }

  @Test
  void wideValidationUsesBoundedProjectionsAndRejectsConcurrentRevisionChanges() {
    var fields =
        IntStream.range(0, 120).mapToObj(index -> field(index, index == 119, null)).toList();
    var document = document(fields);
    var plan = new Plan(document, 2, "b".repeat(64), List.of(document));
    var metadata = mock(FormRecordInvariantService.class);
    when(metadata.resolve(any())).thenReturn(plan);
    var dna = mock(DnaFormRuntimeService.class);
    var validator =
        new FormRecordInvariantValidator(metadata, new FormRecordPayloadCompiler(), dna);
    var form = mock(PublishedApplicationForm.class);
    when(form.physicalModelId()).thenReturn("model-a");
    var context = mock(DnaInvocationContext.class);
    when(dna.getRecord(any(), any()))
        .thenAnswer(
            call -> {
              RecordLookupQuery query = call.getArgument(1);
              assertThat(query.fields()).hasSizeLessThanOrEqualTo(100);
              Map<String, Object> values = new LinkedHashMap<>();
              query.fields().forEach(code -> values.put(code, "value"));
              values.put("record_state", "SUBMITTED");
              return new RecordView("record-a", 4, values);
            });
    validator.update(form, context, "record-a", 4, Map.of(), FormRecordState.SUBMITTED);
    verify(dna, times(2)).getRecord(any(), any());
    var count = new AtomicInteger();
    doAnswer(
            call -> {
              RecordLookupQuery query = call.getArgument(1);
              Map<String, Object> values = new LinkedHashMap<>();
              query.fields().forEach(code -> values.put(code, "value"));
              values.put("record_state", "SUBMITTED");
              return new RecordView("record-a", count.getAndIncrement() == 0 ? 4 : 5, values);
            })
        .when(dna)
        .getRecord(any(), any());
    assertThatThrownBy(
            () ->
                validator.update(form, context, "record-a", 4, Map.of(), FormRecordState.SUBMITTED))
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(ApplicationFormErrorCode.RECORD_CONFLICT));
  }

  @Test
  void finalValueChecksDoNotReadManagedCopiesOrPendingDefaultsThroughThePreflightProjection() {
    var reference =
        new FormFieldDefinition(
            "reference",
            "reference",
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
                    "field-0",
                    org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping.Mode
                        .SERVER_SNAPSHOT,
                    org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping
                        .Conversion.IDENTITY,
                    org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping
                        .NullPolicy.CLEAR),
                new org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping(
                    "default",
                    "name",
                    "field-1",
                    org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping.Mode
                        .EDITABLE_DEFAULT,
                    org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping
                        .Conversion.IDENTITY,
                    org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping
                        .NullPolicy.CLEAR)));
    var document =
        new ApplicationFormDocument(
            "1",
            List.of(reference, field(0, true, null), field(1, true, null), field(2, true, null)),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            null,
            List.of(relation));
    final var mapping =
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
                        .FormReferenceMappingObservation(
                        "a".repeat(64), "11111111-1111-1111-1111-111111111111", 0L),
                    List.of("default"))));
    var metadata = mock(FormRecordInvariantService.class);
    when(metadata.resolve(any()))
        .thenReturn(new Plan(document, 2, "a".repeat(64), List.of(document)));
    var dna = mock(DnaFormRuntimeService.class);
    var form = mock(PublishedApplicationForm.class);
    when(form.physicalModelId()).thenReturn("model");
    var context = mock(DnaInvocationContext.class);
    var validator =
        new FormRecordInvariantValidator(metadata, new FormRecordPayloadCompiler(), dna);
    when(dna.getRecord(any(), any()))
        .thenAnswer(
            call -> {
              RecordLookupQuery query = call.getArgument(1);
              assertThat(query.fields()).containsExactly("column_2", "record_state");
              return new RecordView(
                  "record", 1, Map.of("column_2", "ordinary", "record_state", "DRAFT"));
            });
    validator.update(form, context, "record", 1, Map.of(), FormRecordState.SUBMITTED, mapping);
    org.mockito.Mockito.doReturn(
            new RecordView("record", 1, Map.of("column_2", "", "record_state", "DRAFT")))
        .when(dna)
        .getRecord(any(), any());
    assertThatThrownBy(
            () ->
                validator.update(
                    form, context, "record", 1, Map.of(), FormRecordState.SUBMITTED, mapping))
        .isInstanceOf(ApplicationFormException.class);
  }

  private static FormFieldDefinition field(
      final int number, final boolean required, final FormValue defaultValue) {
    return new FormFieldDefinition(
        "field-" + number,
        "column_" + number,
        "Field " + number,
        "Field " + number,
        FormFieldType.STRING,
        FormStorageMode.STORED,
        required,
        false,
        128,
        null,
        null,
        defaultValue,
        List.of());
  }

  private static ApplicationFormDocument document(final List<FormFieldDefinition> fields) {
    return new ApplicationFormDocument("1", fields, List.of(), List.of(), List.of());
  }
}
