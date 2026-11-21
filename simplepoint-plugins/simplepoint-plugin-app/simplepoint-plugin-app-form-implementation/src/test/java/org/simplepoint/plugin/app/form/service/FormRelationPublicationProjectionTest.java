package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormDetailDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationCardinality;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationField;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;
import org.simplepoint.plugin.app.form.entity.ApplicationFormVersionEntity;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;

/**
 * Immutable version -> root/detail storage -> installed record governance, without target relookup.
 */
class FormRelationPublicationProjectionTest {
  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
  private static final FormDocumentCodec DOCUMENTS = new FormDocumentCodec(MAPPER);
  private static final FormStorageProjectionCompiler STORAGE =
      new FormStorageProjectionCompiler(MAPPER);

  @Test
  void oneFrozenSnapshotFeedsMainDetailAndReferenceOnlyGovernance() throws Exception {
    var document = document();
    var bindings = bindings(document);
    var version = version(document, bindings);
    var parent = STORAGE.decode(version.getStorageSchemaJson());
    assertThat(parent.tenantRecordIdentity()).isTrue();
    assertThat(parent.references())
        .singleElement()
        .satisfies(
            reference -> {
              assertThat(reference.fieldId()).isEqualTo("customer");
              assertThat(reference.targetModelId()).isEqualTo("model-customer");
              assertThat(reference.unique()).isTrue();
            });
    var detail =
        STORAGE.compileDetail(document, document.details().getFirst(), "model-source", bindings);
    assertThat(detail.schema().owner().parentModelId()).isEqualTo("model-source");
    assertThat(detail.schema().references())
        .singleElement()
        .satisfies(
            reference -> {
              assertThat(reference.fieldId()).isEqualTo("product");
              assertThat(reference.targetModelId()).isEqualTo("model-product");
              assertThat(reference.unique()).isFalse();
            });
    var projection =
        new FormCalculationPublicationProjection(DOCUMENTS, STORAGE, MAPPER, Clock.systemUTC());
    var policy = projection.compile(version, "model-source", false);
    assertThat(policy).isNotNull();
    assertThat(policy.formulas()).isEmpty();
    assertThat(policy.referencesOrEmpty())
        .hasSize(2)
        .anySatisfy(
            rule -> {
              assertThat(rule.source().scope()).isEqualTo("lines");
              assertThat(rule.targetAppId()).isEqualTo("catalog-app");
              assertThat(rule.targetResourceCode()).isEqualTo("resource-product");
            });
    assertThat(policy.details().get("lines").schemaReference().checksum())
        .isEqualTo(detail.checksum());
    assertThat(DOCUMENTS.decodeRelationBindings(document, version.getRelationBindingsJson()))
        .containsExactlyElementsOf(bindings);
  }

  @Test
  void bindingChecksumRejectsTamperingMissingSnapshotsAndOtherDocuments() {
    var document = document();
    String json = DOCUMENTS.encodeRelationBindings(document, bindings(document));
    for (String bad :
        List.of(
            "",
            "{}",
            json.replace("model-product", "model-attacker"),
            json.replace("\"contractVersion\":1", "\"contractVersion\":2"))) {
      persistence(() -> DOCUMENTS.decodeRelationBindings(document, bad));
    }
    persistence(() -> DOCUMENTS.decodeRelationBindings(document, null));
    var changed =
        new ApplicationFormDocument(
            "1",
            List.of(reference("customer", "renamed_customer")),
            List.of(),
            List.of(),
            List.of(),
            document.details(),
            List.of(),
            null,
            document.relations());
    persistence(() -> DOCUMENTS.decodeRelationBindings(changed, json));
    assertThat(DOCUMENTS.encodeRelationBindings(document, bindings(document).reversed()))
        .isEqualTo(json);
  }

  @Test
  void missingDuplicateAndRetargetedBindingsCannotBecomeUnconstrainedUuidColumns() {
    var document = document();
    var bindings = bindings(document);
    var first = bindings.getFirst();
    var wrong =
        new FormRelationBinding(
            first.relationId(),
            new FormRelationTarget("other-app", "customer", "selected-customer"),
            first.targetPageId(),
            first.targetResourceCode(),
            first.targetPhysicalModelId());
    for (var bad :
        List.of(
            List.<FormRelationBinding>of(),
            List.of(first, first),
            List.of(wrong, bindings.getLast()))) {
      persistence(() -> STORAGE.compile(document, bad, true));
    }
    persistence(() -> STORAGE.compile(document));
    persistence(() -> DOCUMENTS.encodeRelationBindings(document, List.of()));
  }

  @Test
  void legacyVersionsAndAcceptedSnapshotsKeepAbsentOptionalMetadata() throws Exception {
    var plain = new ApplicationFormDocument("1", List.of(), List.of(), List.of(), List.of());
    assertThat(DOCUMENTS.encodeRelationBindings(plain, List.of())).isNull();
    assertThat(DOCUMENTS.decodeRelationBindings(plain, null)).isEmpty();
    assertThat(STORAGE.compile(plain).json()).doesNotContain("tenantRecordIdentity", "references");
    var runtime =
        new PublishedApplicationForm(
            "tenant",
            "app",
            "page",
            "Page",
            "form",
            "Form",
            1,
            "resource",
            "model",
            1,
            "a".repeat(64),
            plain);
    String json = MAPPER.writeValueAsString(runtime);
    assertThat(json).doesNotContain("relationBindings");
    assertThat(MAPPER.readValue(json, PublishedApplicationForm.class)).isEqualTo(runtime);
  }

  @Test
  void publicationRecompilesOnlyFrozenBindingsAndDetectsDivergentStoredProjections()
      throws Exception {
    var document = document();
    var bindings = bindings(document);
    var version = version(document, bindings);
    var projection =
        new FormCalculationPublicationProjection(DOCUMENTS, STORAGE, MAPPER, Clock.systemUTC());
    var original = projection.compile(version, "model-source", false);
    assertThat(projection.compile(version, "model-source", true)).isEqualTo(original);
    var changed =
        bindings.stream()
            .map(
                binding ->
                    new FormRelationBinding(
                        binding.relationId(),
                        binding.target(),
                        binding.targetPageId(),
                        binding.targetResourceCode(),
                        "replacement-model"))
            .toList();
    version.setRelationBindingsJson(DOCUMENTS.encodeRelationBindings(document, changed));
    persistence(() -> projection.compile(version, "model-source", false));
  }

  static ApplicationFormDocument document() {
    var lines =
        new FormDetailDefinition(
            "lines", "lines", "Goods", List.of(reference("product", "product_id")), List.of());
    return new ApplicationFormDocument(
        "1",
        List.of(reference("customer", "customer_id")),
        List.of(),
        List.of(),
        List.of(),
        List.of(lines),
        List.of(),
        null,
        List.of(
            new FormRelationDefinition(
                "1",
                "customer",
                FormRelationCardinality.ONE_TO_ONE,
                new FormRelationField(null, "customer"),
                new FormRelationTarget("customer-app", "customer", "selected-customer")),
            new FormRelationDefinition(
                "1",
                "product",
                FormRelationCardinality.MANY_TO_ONE,
                new FormRelationField("lines", "product"),
                new FormRelationTarget("catalog-app", "product", "selected-product"))));
  }

  static List<FormRelationBinding> bindings(ApplicationFormDocument document) {
    return document.relations().stream()
        .map(
            relation ->
                new FormRelationBinding(
                    relation.relationId(),
                    relation.target(),
                    "page-" + relation.relationId(),
                    "resource-" + relation.relationId(),
                    "model-" + relation.relationId()))
        .toList();
  }

  private static ApplicationFormVersionEntity version(
      ApplicationFormDocument document, List<FormRelationBinding> bindings) throws Exception {
    var encoded = DOCUMENTS.encode(document);
    var storage = STORAGE.compile(document, bindings, true);
    var version = new ApplicationFormVersionEntity();
    version.setDocumentJson(encoded.json());
    version.setChecksum(encoded.checksum());
    version.setStorageSchemaJson(storage.json());
    version.setStorageChecksum(storage.checksum());
    version.setRelationBindingsJson(DOCUMENTS.encodeRelationBindings(document, bindings));
    version.setPhysicalModelVersion(1);
    version.setDetailBindingsJson(
        MAPPER.writeValueAsString(
            document.details().stream()
                .map(
                    detail ->
                        new FormDetailBinding(
                            detail.detailId(),
                            "model-" + detail.detailId(),
                            1,
                            STORAGE
                                .compileDetail(document, detail, "model-source", bindings)
                                .checksum()))
                .toList()));
    return version;
  }

  private static FormFieldDefinition reference(String id, String code) {
    return new FormFieldDefinition(
        id,
        code,
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

  private static void persistence(Runnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(ApplicationFormErrorCode.PERSISTENCE_FAILED));
  }
}
