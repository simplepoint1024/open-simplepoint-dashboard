package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormCondition;
import org.simplepoint.plugin.app.form.api.model.FormConditionOperator;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceView;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationQualification;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceField;

class FormRelationDependenciesTest {
  @Test
  void freezesDisplaySearchAndNestedCandidateAndQualificationFieldsOnce() {
    var original = FormRelationPublicationProjectionTest.document().relations().getFirst();
    var relation =
        new FormRelationDefinition(
            original.contractVersion(),
            original.relationId(),
            original.cardinality(),
            original.sourceField(),
            original.target(),
            new FormRelationQualification(
                present("eligible"), FormRelationQualification.Recheck.ALWAYS),
            new FormReferenceView(
                "name",
                List.of("name", "code"),
                List.of("name", "balance"),
                new FormCondition(
                    FormConditionOperator.AND, null, List.of(), List.of(present("region"))),
                true));
    var target =
        target(
            List.of(
                field("name", FormFieldType.STRING),
                field("code", FormFieldType.STRING),
                field("balance", FormFieldType.DECIMAL),
                field("region", FormFieldType.UUID),
                field("eligible", FormFieldType.BOOLEAN)));
    assertThat(FormRelationDependencies.freeze(relation, target))
        .containsExactly(
            new LogicalReferenceField("balance", LogicalFieldType.DECIMAL),
            new LogicalReferenceField("code", LogicalFieldType.STRING),
            new LogicalReferenceField("eligible", LogicalFieldType.BOOLEAN),
            new LogicalReferenceField("name", LogicalFieldType.STRING),
            new LogicalReferenceField("region", LogicalFieldType.UUID));
    assertThatThrownBy(() -> FormRelationDependencies.freeze(relation, target(List.of())))
        .isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void frozenTypesReachRootAndDetailSchemasAndParticipateInBindingChecksum() {
    var document = FormRelationPublicationProjectionTest.document();
    var dependencies = List.of(new LogicalReferenceField("name", LogicalFieldType.STRING));
    var bindings =
        FormRelationPublicationProjectionTest.bindings(document).stream()
            .map(
                value ->
                    new FormRelationBinding(
                        value.relationId(),
                        value.target(),
                        value.targetPageId(),
                        value.targetResourceCode(),
                        value.targetPhysicalModelId(),
                        dependencies))
            .toList();
    var mapper = new ObjectMapper();
    var codec = new FormDocumentCodec(mapper);
    var encoded = codec.encodeRelationBindings(document, bindings);
    assertThat(codec.decodeRelationBindings(document, encoded)).isEqualTo(bindings);
    assertThatThrownBy(
            () -> codec.decodeRelationBindings(document, encoded.replace("STRING", "BOOLEAN")))
        .isInstanceOf(ApplicationFormException.class);
    var compiler = new FormStorageProjectionCompiler(mapper);
    assertThat(
            compiler
                .compile(document, bindings, true)
                .schema()
                .references()
                .getFirst()
                .targetFields())
        .isEqualTo(dependencies);
    assertThat(
            compiler
                .compileDetail(document, document.details().getFirst(), "source", bindings)
                .schema()
                .references()
                .getFirst()
                .targetFields())
        .isEqualTo(dependencies);
  }

  @Test
  void maximumBoundedDependencyEnvelopeRemainsReadable() {
    var original = FormRelationPublicationProjectionTest.document().relations().getFirst();
    var relations =
        java.util.stream.IntStream.range(0, 50)
            .mapToObj(
                i ->
                    new FormRelationDefinition(
                        "1",
                        "relation" + i,
                        original.cardinality(),
                        new org.simplepoint.plugin.app.form.api.model.relation.FormRelationField(
                            null, "field" + i),
                        original.target()))
            .toList();
    var fields =
        java.util.stream.IntStream.range(0, 50)
            .mapToObj(
                i ->
                    new FormFieldDefinition(
                        "field" + i,
                        "field" + i,
                        "Field",
                        "Field",
                        FormFieldType.UUID,
                        FormStorageMode.REFERENCE,
                        false,
                        false,
                        null,
                        null,
                        null,
                        null,
                        List.of()))
            .toList();
    var document =
        new ApplicationFormDocument(
            "1", fields, List.of(), List.of(), List.of(), List.of(), List.of(), null, relations);
    var dependencies =
        java.util.stream.IntStream.range(0, 128)
            .mapToObj(
                i ->
                    new LogicalReferenceField(
                        "field" + "x".repeat(55) + i, LogicalFieldType.STRING))
            .toList();
    var bindings =
        relations.stream()
            .map(
                relation ->
                    new FormRelationBinding(
                        relation.relationId(),
                        relation.target(),
                        "page",
                        "resource",
                        "model",
                        dependencies))
            .toList();
    var codec = new FormDocumentCodec(new ObjectMapper());
    String encoded = codec.encodeRelationBindings(document, bindings);
    assertThat(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
        .isGreaterThan(131_072)
        .isLessThan(1024 * 1024);
    assertThat(codec.decodeRelationBindings(document, encoded))
        .containsExactlyInAnyOrderElementsOf(bindings);
  }

  @Test
  void legacyBindingsDoNotAcquireOptionalMetadata() throws Exception {
    var document = FormRelationPublicationProjectionTest.document();
    var mapper = new ObjectMapper();
    var binding = FormRelationPublicationProjectionTest.bindings(document).getFirst();
    String json = mapper.writeValueAsString(binding);
    assertThat(json).doesNotContain("targetFields");
    assertThat(mapper.readValue(json, FormRelationBinding.class)).isEqualTo(binding);
  }

  private static FormCondition present(String id) {
    return new FormCondition(FormConditionOperator.IS_NOT_EMPTY, id, List.of(), List.of());
  }

  private static ApplicationFormDocument target(List<FormFieldDefinition> fields) {
    return new ApplicationFormDocument("1", fields, List.of(), List.of(), List.of());
  }

  private static FormFieldDefinition field(String id, FormFieldType type) {
    return new FormFieldDefinition(
        id,
        id,
        id,
        id,
        type,
        FormStorageMode.STORED,
        false,
        false,
        type == FormFieldType.STRING ? 100 : null,
        type == FormFieldType.DECIMAL ? 10 : null,
        type == FormFieldType.DECIMAL ? 2 : null,
        null,
        List.of());
  }
}
