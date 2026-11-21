package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormDetailDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping.Conversion;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping.Mode;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping.NullPolicy;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationCardinality;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationField;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;

class FormReferenceMappingTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final FormDocumentCodec codec = new FormDocumentCodec(mapper);

  @Test
  void legacyRelationKeepsExactJsonAndCanonicalChecksum() throws Exception {
    String json =
        """
        {"contractVersion":"1","relationId":"customer","cardinality":"MANY_TO_ONE",
        "sourceField":{"fieldId":"customer"},
        "target":{"applicationId":"app","formId":"customers","accessResourceId":"customers-page"}}
        """
            .replace("\n", "");
    var original = mapper.readValue(json, FormRelationDefinition.class);
    assertThat(original.mappings()).isEmpty();
    assertThat(mapper.writeValueAsString(original)).isEqualTo(json);
    var first = document(List.of(reference("customer")), List.of(), List.of(original));
    var decoded = mapper.readValue(codec.encode(first).json(), ApplicationFormDocument.class);
    assertThat(codec.encode(decoded)).isEqualTo(codec.encode(first));
    assertThat(codec.encode(first).json()).doesNotContain("mappings");
  }

  @Test
  void mappingsRoundTripAndSortWithoutLosingSemanticsOrImmutableAbsence() throws Exception {
    var first = mapping("address", Mode.SERVER_SNAPSHOT);
    var second = mapping("name", Mode.EDITABLE_DEFAULT);
    var fields =
        List.of(
            reference("customer"),
            field("address", FormStorageMode.STORED),
            field("name", FormStorageMode.STORED));
    var original =
        document(fields, List.of(), List.of(relation("customer", null, List.of(first, second))));
    var reordered =
        document(fields, List.of(), List.of(relation("customer", null, List.of(second, first))));
    assertThat(codec.encode(original).checksum()).isEqualTo(codec.encode(reordered).checksum());
    assertThat(mapper.readValue(codec.encode(original).json(), ApplicationFormDocument.class))
        .isEqualTo(original);
    var changed =
        document(
            fields,
            List.of(),
            List.of(
                relation(
                    "customer", null, List.of(mapping("address", Mode.EDITABLE_DEFAULT), second))));
    assertThat(codec.encode(original).checksum()).isNotEqualTo(codec.encode(changed).checksum());
    FormRelationDocumentValidator.validate(original);
  }

  @Test
  void rootAndDetailProvidersRemainDistinctButDuplicateOrStaticProvidersAreRejected() {
    var fields = List.of(reference("customer"), field("address", FormStorageMode.STORED));
    var detail = new FormDetailDefinition("lines", "lines", "明细", fields, List.of());
    var root = relation("customer", null, List.of(mapping("address", Mode.SERVER_SNAPSHOT)));
    var child = relation("customer", "lines", root.mappings());
    FormRelationDocumentValidator.validate(document(fields, List.of(detail), List.of(root, child)));
    var duplicate = relation("supplier", null, root.mappings());
    var extra = new ArrayList<>(fields);
    extra.add(reference("supplier"));
    assertInvalid(
        () ->
            FormRelationDocumentValidator.validate(
                document(extra, List.of(), List.of(root, duplicate))));
    assertInvalid(
        () ->
            FormRelationDocumentValidator.validate(
                document(
                    fields,
                    List.of(),
                    List.of(
                        relation(
                            "customer",
                            null,
                            List.of(mapping("customer", Mode.SERVER_SNAPSHOT)))))));
    var preset =
        new FormFieldDefinition(
            "address",
            "address",
            "Address",
            "Address",
            FormFieldType.STRING,
            FormStorageMode.STORED,
            false,
            false,
            100,
            null,
            null,
            new FormValue(FormValueKind.STRING, "preset"),
            List.of());
    assertInvalid(
        () ->
            FormRelationDocumentValidator.validate(
                document(List.of(reference("customer"), preset), List.of(), List.of(root))));
  }

  @Test
  void liveProjectionsNeverPersistAndCollectionsCannotChooseAnImplicitFirstRecord() {
    var live = relation("customer", null, List.of(mapping("address", Mode.LIVE)));
    FormRelationDocumentValidator.validate(
        document(
            List.of(reference("customer"), field("address", FormStorageMode.TRANSIENT)),
            List.of(),
            List.of(live)));
    assertInvalid(
        () ->
            FormRelationDocumentValidator.validate(
                document(
                    List.of(reference("customer"), field("address", FormStorageMode.STORED)),
                    List.of(),
                    List.of(live))));
    assertThatThrownBy(
            () ->
                new FormRelationDefinition(
                    "1",
                    "customers",
                    FormRelationCardinality.MANY_TO_MANY,
                    null,
                    live.target(),
                    null,
                    null,
                    live.mappings()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new FormReferenceMapping(
                    "address",
                    "address",
                    "address",
                    Mode.LIVE,
                    Conversion.IDENTITY,
                    NullPolicy.PRESERVE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new FormReferenceMapping(
                    "address",
                    "address",
                    "address",
                    Mode.SERVER_SNAPSHOT,
                    Conversion.IDENTITY,
                    NullPolicy.PRESERVE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void mappingBudgetsAndDestinationUniquenessAreBoundedBeforePublication() {
    assertThatThrownBy(
            () ->
                relation(
                    "customer",
                    null,
                    List.of(
                        mapping("address", Mode.SERVER_SNAPSHOT),
                        mapping("address", Mode.EDITABLE_DEFAULT))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                relation(
                    "customer",
                    null,
                    IntStream.range(0, 33)
                        .mapToObj(i -> mapping("field" + i, Mode.SERVER_SNAPSHOT))
                        .toList()))
        .isInstanceOf(IllegalArgumentException.class);
    var fields = new ArrayList<FormFieldDefinition>();
    var relations = new ArrayList<FormRelationDefinition>();
    for (int owner = 0; owner < 5; owner++) {
      String id = "customer" + owner;
      fields.add(reference(id));
      var mappings = new ArrayList<FormReferenceMapping>();
      for (int index = 0; index < 26; index++) {
        String destination = "value" + owner + "_" + index;
        fields.add(field(destination, FormStorageMode.STORED));
        mappings.add(mapping(destination, Mode.SERVER_SNAPSHOT));
      }
      relations.add(relation(id, null, mappings));
    }
    assertInvalid(
        () -> FormRelationDocumentValidator.validate(document(fields, List.of(), relations)));
  }

  @Test
  void targetFieldsAreFrozenAndConversionFamiliesAreExplicit() {
    var relation = relation("customer", null, List.of(mapping("address", Mode.SERVER_SNAPSHOT)));
    var source =
        document(
            List.of(reference("customer"), field("address", FormStorageMode.STORED)),
            List.of(),
            List.of(relation));
    var target = document(List.of(field("address", FormStorageMode.STORED)), List.of(), List.of());
    FormReferenceMappingPolicy.validateTarget(source, relation, target);
    assertThat(FormRelationDependencies.freeze(relation, target))
        .extracting(value -> value.fieldId())
        .containsExactly("address");
    assertInvalid(
        () ->
            FormReferenceMappingPolicy.validateTarget(
                source,
                relation,
                document(
                    List.of(field("address", FormStorageMode.TRANSIENT)), List.of(), List.of())));
    assertThat(
            FormReferenceMappingPolicy.compatible(
                Conversion.DECIMAL, FormFieldType.STRING, FormFieldType.DECIMAL))
        .isTrue();
    assertThat(
            FormReferenceMappingPolicy.compatible(
                Conversion.INTEGER, FormFieldType.DECIMAL, FormFieldType.INT64))
        .isTrue();
    assertThat(
            FormReferenceMappingPolicy.compatible(
                Conversion.INTEGER, FormFieldType.DATE, FormFieldType.INT64))
        .isFalse();
    assertThat(
            FormReferenceMappingPolicy.compatible(
                Conversion.IDENTITY, FormFieldType.INT64, FormFieldType.INT32))
        .isFalse();
    assertThat(
            FormReferenceMappingPolicy.compatible(
                Conversion.STRING, FormFieldType.JSON, FormFieldType.STRING))
        .isFalse();
  }

  @Test
  void storageGovernanceCarriesCopiesButDoesNotTurnLiveDisplayIntoStoredWrites() {
    var relation =
        relation(
            "customer",
            null,
            List.of(
                mapping("address", Mode.SERVER_SNAPSHOT),
                mapping("name", Mode.EDITABLE_DEFAULT),
                mapping("live", Mode.LIVE)));
    var document =
        document(
            List.of(
                reference("customer"),
                field("address", FormStorageMode.STORED),
                field("name", FormStorageMode.STORED),
                field("live", FormStorageMode.TRANSIENT)),
            List.of(),
            List.of(relation));
    var binding =
        new FormRelationBinding(
            relation.relationId(), relation.target(), "page", "resource", "target-model");
    var rule = FormRelationPublicationProjection.rules(document, List.of(binding)).getFirst();
    assertThat(rule.mappings())
        .extracting(value -> value.destinationFieldId())
        .containsExactly("address", "name");
    assertThat(rule.mappings().getFirst().mode().name()).isEqualTo("SERVER_SNAPSHOT");
    assertThat(rule.mappings().get(1).mode().name()).isEqualTo("EDITABLE_DEFAULT");
  }

  @Test
  void mappedDraftUsesTheOrdinaryRelationPublicationValidation() {
    var document =
        document(
            List.of(reference("customer"), field("address", FormStorageMode.STORED)),
            List.of(),
            List.of(relation("customer", null, List.of(mapping("address", Mode.SERVER_SNAPSHOT)))));
    FormRelationDocumentValidator.validate(document);
    FormRelationPublicationValidator.validate(document);
  }

  private static FormFieldDefinition reference(String id) {
    return new FormFieldDefinition(
        id,
        id,
        id,
        id,
        FormFieldType.UUID,
        FormStorageMode.REFERENCE,
        false,
        false,
        null,
        null,
        null,
        null,
        List.of());
  }

  private static FormFieldDefinition field(String id, FormStorageMode mode) {
    return new FormFieldDefinition(
        id, id, id, id, FormFieldType.STRING, mode, false, false, 100, null, null, null, List.of());
  }

  private static FormReferenceMapping mapping(String destination, Mode mode) {
    return new FormReferenceMapping(
        "map-" + destination,
        destination,
        destination,
        mode,
        Conversion.IDENTITY,
        NullPolicy.CLEAR);
  }

  private static FormRelationDefinition relation(
      String field, String detail, List<FormReferenceMapping> mappings) {
    return new FormRelationDefinition(
        "1",
        (detail == null ? "" : detail + "-") + field,
        FormRelationCardinality.MANY_TO_ONE,
        new FormRelationField(detail, field),
        new FormRelationTarget("app", "customers", "customers-page"),
        null,
        null,
        mappings);
  }

  private static ApplicationFormDocument document(
      List<FormFieldDefinition> fields,
      List<FormDetailDefinition> details,
      List<FormRelationDefinition> relations) {
    return new ApplicationFormDocument(
        "1", fields, List.of(), List.of(), List.of(), details, null, null, relations);
  }

  private static void assertInvalid(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(ApplicationFormErrorCode.DOCUMENT_INVALID));
  }
}
