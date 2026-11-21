package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingConfirmation;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingIntent;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingObservation;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationCardinality;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationField;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordModel;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordRow;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordSet;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope;

class FormReferenceMappedIntentTest {
  private static final String TARGET = "11111111-1111-1111-1111-111111111111";
  private static final String ROW = "22222222-2222-2222-2222-222222222222";
  private static final FormReferenceMappingObservation OBSERVATION =
      new FormReferenceMappingObservation("a".repeat(64), TARGET, 1L);

  @Test
  void canonicalIntentRoundTripsAndRejectsAmbiguousCoordinates() throws Exception {
    var root = confirmation("customer", null, null, null);
    var row = confirmation("product", "lines", 0, ROW);
    var intent = new FormReferenceMappingIntent("1", List.of(row, root));
    assertThat(intent.confirmations()).containsExactly(root, row);
    var json = JsonMapper.builder().build();
    assertThat(json.readValue(json.writeValueAsString(intent), FormReferenceMappingIntent.class))
        .isEqualTo(intent);
    assertThatThrownBy(() -> new FormReferenceMappingIntent("1", List.of(root, root)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> confirmation("customer", null, 0, ROW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> confirmation("product", "lines", 40, ROW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void translatesOnlyPublishedDetailIdentityAndRetainsInverseScope() {
    var form = form();
    var command = command(List.of(new OwnedRecordRow(ROW, 0L, Map.of("product_id", TARGET))));
    var scope =
        new RelatedRecordScope(
            "1", "customer", "target-model", TARGET, RelatedRecordScope.Mode.MAINTAIN, null);
    var intent =
        new FormReferenceMappingIntent(
            "1",
            List.of(
                confirmation("product", "lines", 0, ROW),
                confirmation("customer", null, null, null)));
    var mapped = FormReferenceMappedIntent.bind(form, command, scope, intent);
    assertThat(mapped.command()).isSameAs(command);
    assertThat(mapped.relatedScope()).isEqualTo(scope);
    assertThat(mapped.confirmations().get(1).detailModelId()).isEqualTo("physical-lines");
    assertThat(mapped.confirmations().get(1).sourceRowId()).isEqualTo(ROW);
    assertThat(mapped.confirmations().get(1).observation().targetRecordId()).isEqualTo(TARGET);
  }

  @Test
  void rowReorderingPhysicalIdentityInjectionAndUnknownDefaultsFailClosed() {
    var form = form();
    var command =
        command(
            List.of(
                new OwnedRecordRow(null, null, Map.of("product_id", TARGET)),
                new OwnedRecordRow(ROW, 0L, Map.of("product_id", TARGET))));
    var stale =
        new FormReferenceMappingIntent("1", List.of(confirmation("product", "lines", 0, ROW)));
    assertThatThrownBy(() -> FormReferenceMappedIntent.bind(form, command, null, stale))
        .isInstanceOf(ApplicationFormException.class);
    var physical =
        new FormReferenceMappingIntent(
            "1", List.of(confirmation("product", "physical-lines", 1, ROW)));
    assertThatThrownBy(() -> FormReferenceMappedIntent.bind(form, command, null, physical))
        .isInstanceOf(ApplicationFormException.class);
    var unknown =
        new FormReferenceMappingIntent(
            "1",
            List.of(
                new FormReferenceMappingConfirmation(
                    "product", "lines", 1, ROW, OBSERVATION, List.of("unknown"))));
    assertThatThrownBy(() -> FormReferenceMappedIntent.bind(form, command, null, unknown))
        .isInstanceOf(ApplicationFormException.class);
    var moved =
        new FormReferenceMappingIntent("1", List.of(confirmation("product", "lines", 1, ROW)));
    assertThat(
            FormReferenceMappedIntent.bind(form, command, null, moved)
                .confirmations()
                .getFirst()
                .rowIndex())
        .isEqualTo(1);
  }

  private static FormReferenceMappingConfirmation confirmation(
      String relation, String detail, Integer index, String row) {
    return new FormReferenceMappingConfirmation(
        relation, detail, index, row, OBSERVATION, List.of());
  }

  private static PublishedApplicationForm form() {
    var form = mock(PublishedApplicationForm.class);
    when(form.document())
        .thenReturn(
            new ApplicationFormDocument(
                "1",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                List.of(relation("customer", null), relation("product", "lines"))));
    when(form.detailBindings())
        .thenReturn(List.of(new FormDetailBinding("lines", "physical-lines", 1, "a".repeat(64))));
    return form;
  }

  private static FormRelationDefinition relation(String id, String detail) {
    return new FormRelationDefinition(
        "1",
        id,
        FormRelationCardinality.MANY_TO_ONE,
        new FormRelationField(detail, id),
        new FormRelationTarget("app", "target", "target-page"),
        null,
        null,
        List.of(
            new FormReferenceMapping(
                "copy",
                "name",
                "label",
                FormReferenceMapping.Mode.SERVER_SNAPSHOT,
                FormReferenceMapping.Conversion.IDENTITY,
                FormReferenceMapping.NullPolicy.CLEAR)));
  }

  private static OwnedRecordCommand command(List<OwnedRecordRow> rows) {
    var schema = new RecordSchemaReference(1, "a".repeat(64));
    return new OwnedRecordCommand(
        new RecordTransactionCommand(
            "root",
            schema,
            List.of(new RecordCreateMutation("parent", Map.of("customer_id", TARGET))),
            30_000,
            "key",
            1),
        List.of(new OwnedRecordSet(new OwnedRecordModel("physical-lines", schema), rows)),
        null);
  }
}
