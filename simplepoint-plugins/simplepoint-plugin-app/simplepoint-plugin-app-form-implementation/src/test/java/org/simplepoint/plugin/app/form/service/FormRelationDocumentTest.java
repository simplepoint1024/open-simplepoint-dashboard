package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormDetailDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationCardinality;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationField;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;

class FormRelationDocumentTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final FormDocumentCodec codec = new FormDocumentCodec(mapper);
  private final FormDocumentValidator validator =
      new FormDocumentValidator(new DefaultFormComponentRegistry());

  @Test
  void legacyDocumentKeepsExactJsonAndChecksum() throws Exception {
    String original =
        "{\"contractVersion\":\"1\",\"fields\":[],\"indexes\":[],\"scenes\":[],\"rules\":[]}";
    var decoded = mapper.readValue(original, ApplicationFormDocument.class);
    var encoded = codec.encode(decoded);
    assertThat(encoded.json()).isEqualTo(original);
    assertThat(encoded.checksum())
        .isEqualTo(
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(original.getBytes(StandardCharsets.UTF_8))));
    assertThat(decoded.relations()).isEmpty();
  }

  @Test
  void relationsRoundTripAndAreCanonicalButChangingTargetChangesChecksum() throws Exception {
    var customer = relation("customer-relation", null, "customer");
    var supplier = relation("supplier-relation", null, "supplier");
    var first =
        document(
            List.of(reference("customer"), reference("supplier")),
            List.of(),
            List.of(customer, supplier));
    var reordered = document(first.fields(), List.of(), List.of(supplier, customer));
    assertThat(codec.encode(first).checksum()).isEqualTo(codec.encode(reordered).checksum());
    assertThat(mapper.readValue(codec.encode(first).json(), ApplicationFormDocument.class))
        .isEqualTo(first);
    var rebound =
        new FormRelationDefinition(
            "1",
            customer.relationId(),
            customer.cardinality(),
            customer.sourceField(),
            new FormRelationTarget("app", "other-customer-form", "customer-page"));
    assertThat(
            codec
                .encode(document(first.fields(), List.of(), List.of(rebound, supplier)))
                .checksum())
        .isNotEqualTo(codec.encode(first).checksum());
    assertThat(FormWritePolicyProjection.apply(first, reordered).relations())
        .containsExactly(supplier, customer);
  }

  @Test
  void rootAndEachDetailCanReferenceDifferentTargetsWithTheSameLocalFieldId() {
    var detail =
        new FormDetailDefinition(
            "lines", "lines", "交易明细", List.of(reference("customer")), List.of());
    var returns =
        new FormDetailDefinition(
            "returns", "returns", "退货明细", List.of(reference("customer")), List.of());
    var root = relation("root-customer", null, "customer");
    var child =
        new FormRelationDefinition(
            "1",
            "line-customer",
            FormRelationCardinality.MANY_TO_ONE,
            new FormRelationField("lines", "customer"),
            new FormRelationTarget("app", "recipient-form", "recipient-page"));
    var returned =
        new FormRelationDefinition(
            "1",
            "return-customer",
            FormRelationCardinality.MANY_TO_ONE,
            new FormRelationField("returns", "customer"),
            new FormRelationTarget("app", "supplier-form", "supplier-page"));
    var doc =
        document(
            List.of(reference("customer")), List.of(detail, returns), List.of(root, child, returned));
    validator.validate(doc);
    assertThat(codec.decode(codec.encode(doc).json()).relations())
        .containsExactlyInAnyOrder(root, child, returned);
    assertInvalid(document(doc.fields(), doc.details(), List.of(root)));
    assertInvalid(
        document(
            doc.fields(),
            doc.details(),
            List.of(root, child, returned, relation("duplicate", "lines", "customer"))));
    assertInvalid(
        document(
            doc.fields(),
            doc.details(),
            List.of(root, child, returned, relation("unknown", "missing", "customer"))));
    assertInvalid(
        document(
            doc.fields(),
            doc.details(),
            List.of(root, child, returned, relation("unknown", null, "missing"))));
  }

  @Test
  void rejectAmbiguousOwnershipAndStaticUnverifiedTargets() {
    var relation = relation("customer", null, "customer");
    assertInvalid(document(List.of(reference("customer")), List.of(), List.of(relation, relation)));
    var defaulted =
        new FormFieldDefinition(
            "customer",
            "customer",
            "客户",
            "客户",
            FormFieldType.UUID,
            FormStorageMode.REFERENCE,
            false,
            false,
            null,
            null,
            null,
            new FormValue(FormValueKind.UUID, "00000000-0000-0000-0000-000000000001"),
            List.of());
    assertInvalid(document(List.of(defaulted), List.of(), List.of(relation)));
    assertThatThrownBy(
            () ->
                new FormRelationDefinition(
                    "1",
                    "inverse",
                    FormRelationCardinality.ONE_TO_MANY,
                    relation.sourceField(),
                    relation.target()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new FormRelationDefinition(
                    "1",
                    "collection",
                    FormRelationCardinality.MANY_TO_MANY,
                    relation.sourceField(),
                    relation.target()))
        .isInstanceOf(IllegalArgumentException.class);
    var collection =
        new FormRelationDefinition(
            "1", "members", FormRelationCardinality.MANY_TO_MANY, null, relation.target());
    validator.validate(document(List.of(), List.of(), List.of(collection)));
    assertThat(FormRelationCardinality.MANY_TO_ONE.inverse())
        .isEqualTo(FormRelationCardinality.ONE_TO_MANY);
  }

  @Test
  void businessReferenceCanRequireStorageWithoutAStaticDefault() {
    var required = new FormFieldDefinition(
        "customer", "customer", "客户", "客户", FormFieldType.UUID,
        FormStorageMode.REFERENCE, true, true, null, null, null, null, List.of());
    var root = relation("customer", null, "customer");
    var child = relation("line-customer", "lines", "customer");
    var detail = new FormDetailDefinition("lines", "lines", "明细", List.of(required), List.of());

    validator.validatePublication(
        withReferenceInput(document(List.of(required), List.of(detail), List.of(root, child))));
    assertInvalid(document(List.of(required), List.of(), List.of()));
  }

  @Test
  void singleReferencePublicationRequiresResolvedBindingsInTheStorageProjection() {
    var doc =
        document(
            List.of(reference("customer")),
            List.of(),
            List.of(relation("customer", null, "customer")));
    validator.validatePublication(withReferenceInput(doc));
    assertThatThrownBy(
            () -> new FormStorageProjectionCompiler(mapper).compile(doc, List.of(), true))
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(ApplicationFormErrorCode.PERSISTENCE_FAILED));
    validator.validatePublication(document(List.of(), List.of(), List.of()));
  }

  @Test
  void collectionPublicationRequiresNoScalarStorageField() {
    var collection =
        new FormRelationDefinition(
            "1",
            "members",
            FormRelationCardinality.MANY_TO_MANY,
            null,
            new FormRelationTarget("app", "member-form", "member-page"));
    var doc = document(List.of(), List.of(), List.of(collection));
    validator.validate(doc);
    validator.validatePublication(doc);
  }

  @Test
  void referenceComponentAcceptsUnconfiguredDraftButCannotPublishIt() {
    var doc = withReferenceInput(document(List.of(reference("customer")), List.of(), List.of()));
    validator.validate(doc);
    assertThatThrownBy(() -> validator.validatePublication(doc))
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(ApplicationFormErrorCode.DOCUMENT_INVALID));
    var descriptor = new DefaultFormComponentRegistry().find("field.reference", "1").orElseThrow();
    assertThat(descriptor.fieldRequired()).isTrue();
    assertThat(descriptor.supportedFieldTypes()).containsExactly(FormFieldType.UUID);
  }

  @Test
  void referenceComponentsCannotMasqueradeAsStoredUuidInputsOrStaticChoices() {
    var stored =
        new FormFieldDefinition(
            "customer",
            "customer",
            "客户",
            "客户",
            FormFieldType.UUID,
            FormStorageMode.STORED,
            false,
            false,
            null,
            null,
            null,
            null,
            List.of());
    assertInvalid(withReferenceInput(document(List.of(stored), List.of(), List.of())));
    var defaulted =
        new FormFieldDefinition(
            "customer",
            "customer",
            "客户",
            "客户",
            FormFieldType.UUID,
            FormStorageMode.REFERENCE,
            false,
            false,
            null,
            null,
            null,
            new FormValue(FormValueKind.UUID, "00000000-0000-0000-0000-000000000001"),
            List.of());
    assertInvalid(withReferenceInput(document(List.of(defaulted), List.of(), List.of())));
    validator.validate(
        withReferenceInput(
            document(
                List.of(reference("customer")),
                List.of(),
                List.of(relation("customer", null, "customer")))));
  }

  private static ApplicationFormDocument withReferenceInput(ApplicationFormDocument doc) {
    var input =
        new FormComponentDefinition(
            "customer-input",
            "field.reference",
            "1",
            "customer",
            24,
            null,
            null,
            null,
            null,
            false,
            Map.of(),
            List.of());
    var group =
        new FormComponentDefinition(
            "group",
            "layout.group",
            "1",
            null,
            24,
            null,
            null,
            null,
            null,
            false,
            Map.of(),
            List.of(input));
    var root =
        new FormComponentDefinition(
            "root",
            "layout.form",
            "1",
            null,
            24,
            null,
            null,
            null,
            null,
            false,
            Map.of(),
            List.of(group));
    return new ApplicationFormDocument(
        "1",
        doc.fields(),
        doc.indexes(),
        List.of(new FormSceneDefinition("main", "main", "Main", FormSceneType.MAIN, null, root)),
        doc.rules(),
        doc.details(),
        doc.constraints(),
        doc.presentation(),
        doc.relations());
  }

  private void assertInvalid(ApplicationFormDocument doc) {
    assertThatThrownBy(() -> validator.validate(doc))
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(ApplicationFormErrorCode.DOCUMENT_INVALID));
  }

  private static FormRelationDefinition relation(String id, String detail, String field) {
    return new FormRelationDefinition(
        "1",
        id,
        FormRelationCardinality.MANY_TO_ONE,
        new FormRelationField(detail, field),
        new FormRelationTarget("app", "customer-form", "customer-page"));
  }

  private static FormFieldDefinition reference(String id) {
    return new FormFieldDefinition(
        id,
        id,
        "客户",
        "客户",
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

  private static ApplicationFormDocument document(
      List<FormFieldDefinition> fields,
      List<FormDetailDefinition> details,
      List<FormRelationDefinition> relations) {
    var root =
        new FormComponentDefinition(
            "root",
            "layout.form",
            "1",
            null,
            24,
            null,
            null,
            null,
            null,
            false,
            Map.of(),
            List.of());
    var scene = new FormSceneDefinition("main", "main", "Main", FormSceneType.MAIN, null, root);
    return new ApplicationFormDocument(
        "1", fields, List.of(), List.of(scene), List.of(), details, List.of(), null, relations);
  }
}
