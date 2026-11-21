package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormDetailDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceView;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationCardinality;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationField;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;
import org.simplepoint.plugin.app.form.api.model.relation.FormRuntimeReference;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;

class FormReferenceMappingProjectionTest {
  @Test
  void projectsOnlyLocalIdentitiesAndValueModesForRootAndDetailFields() throws Exception {
    for (String detail : new String[] {null, "lines"}) {
      var form = form(detail);
      var document = new FormRuntimeDocumentCompiler().compile(form, Map.of());
      var fields = detail == null ? document.fields() : document.details().getFirst().fields();
      assertThat(fields.getFirst().reference().mappings())
          .extracting(FormRuntimeReference.Mapping::mode)
          .containsExactly(
              FormReferenceMapping.Mode.SERVER_SNAPSHOT,
              FormReferenceMapping.Mode.EDITABLE_DEFAULT,
              FormReferenceMapping.Mode.LIVE);
      assertThat(fields.getFirst().reference().mappings())
          .extracting(FormRuntimeReference.Mapping::nullPolicy)
          .containsExactly(
              FormReferenceMapping.NullPolicy.CLEAR,
              FormReferenceMapping.NullPolicy.CLEAR,
              FormReferenceMapping.NullPolicy.CLEAR);
      assertThat(fields.get(1).referenceValueMode())
          .isEqualTo(FormReferenceMapping.Mode.SERVER_SNAPSHOT);
      assertThat(fields.get(2).referenceValueMode())
          .isEqualTo(FormReferenceMapping.Mode.EDITABLE_DEFAULT);
      assertThat(fields.get(3).referenceValueMode()).isEqualTo(FormReferenceMapping.Mode.LIVE);
      var json = JsonMapper.builder().build().writeValueAsString(document);
      assertThat(json)
          .contains("destinationFieldId")
          .doesNotContain("target-secret-field", "physical-target", "target-app", "target-form");
    }
  }

  @Test
  void hiddenSnapshotDestinationsDisableMappingAndNeverDiscloseTheirIdentities() {
    var form = form("lines");
    var runtime =
        new FormRuntimeDocumentCompiler()
            .compile(
                form,
                Map.of(
                    "dna.runtime.record.physical-lines#stored_copy", "HIDDEN",
                    "dna.runtime.record.physical-lines#stored_default", "VISIBLE",
                    "dna.runtime.record.physical-lines#stored_live", "VISIBLE"));
    var fields = runtime.details().getFirst().fields();
    assertThat(fields).extracting(field -> field.fieldId()).doesNotContain("copy");
    var reference = fields.getFirst().reference();
    assertThat(reference.mappingWritable()).isFalse();
    assertThat(reference.mappings())
        .extracting(FormRuntimeReference.Mapping::destinationFieldId)
        .containsExactly("live");
    var masked =
        new FormRuntimeDocumentCompiler()
            .compile(form, Map.of("dna.runtime.record.physical-lines#stored_reference", "MASKED"));
    assertThat(masked.details().getFirst().fields().getFirst().reference()).isNull();
  }

  @Test
  void ordinaryReferenceWireShapeKeepsOptionalCapabilitiesAbsent() throws Exception {
    var json = JsonMapper.builder().build();
    var legacyMapping =
        new FormRuntimeReference.Mapping(
            "copy", "destination", FormReferenceMapping.Mode.SERVER_SNAPSHOT);
    assertThat(json.writeValueAsString(legacyMapping)).doesNotContain("nullPolicy");
    assertThat(
            json.readValue(
                json.writeValueAsString(legacyMapping), FormRuntimeReference.Mapping.class))
        .isEqualTo(legacyMapping);
    var legacy = new FormRuntimeReference("customer", true);
    String wire = json.writeValueAsString(legacy);
    assertThat(wire).doesNotContain("mappings", "mappingWritable");
    assertThat(json.readValue(wire, FormRuntimeReference.class)).isEqualTo(legacy);
  }

  private static PublishedApplicationForm form(String detail) {
    var form = mock(PublishedApplicationForm.class);
    var fields =
        List.of(
            field("reference", FormStorageMode.REFERENCE),
            field("copy", FormStorageMode.STORED),
            field("default", FormStorageMode.STORED),
            field("live", FormStorageMode.TRANSIENT));
    var relation =
        new FormRelationDefinition(
            "1",
            "customer",
            FormRelationCardinality.MANY_TO_ONE,
            new FormRelationField(detail, "reference"),
            new FormRelationTarget("target-app", "target-form", "target-page"),
            null,
            new FormReferenceView("label", List.of("label"), List.of(), null, true),
            List.of(
                mapping("copy", FormReferenceMapping.Mode.SERVER_SNAPSHOT),
                mapping("default", FormReferenceMapping.Mode.EDITABLE_DEFAULT),
                mapping("live", FormReferenceMapping.Mode.LIVE)));
    var document =
        new ApplicationFormDocument(
            "1",
            detail == null ? fields : List.of(),
            List.of(),
            List.of(),
            List.of(),
            detail == null
                ? List.of()
                : List.of(new FormDetailDefinition(detail, "lines", "Lines", fields, List.of())),
            null,
            null,
            List.of(relation));
    when(form.document()).thenReturn(document);
    when(form.physicalModelId()).thenReturn("physical-root");
    when(form.detailBindings())
        .thenReturn(
            detail == null
                ? List.of()
                : List.of(new FormDetailBinding(detail, "physical-lines", 1, "a".repeat(64))));
    return form;
  }

  private static FormReferenceMapping mapping(String destination, FormReferenceMapping.Mode mode) {
    return new FormReferenceMapping(
        "map-" + destination,
        "target-secret-field",
        destination,
        mode,
        FormReferenceMapping.Conversion.IDENTITY,
        FormReferenceMapping.NullPolicy.CLEAR);
  }

  private static FormFieldDefinition field(String id, FormStorageMode mode) {
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
        List.of());
  }
}
