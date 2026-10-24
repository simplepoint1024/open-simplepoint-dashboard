package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.ApplicationFormApiVersion;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormIndexDefinition;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;

/** Proves checksums use normalized schema semantics rather than editor list order. */
class FormDocumentCodecTest {

  private final FormDocumentCodec codec = new FormDocumentCodec(
      new ObjectMapper().findAndRegisterModules()
  );

  @Test
  void ignoresNonVisualFieldAndIndexDeclarationOrder() {
    FormFieldDefinition alpha = field("alpha");
    FormFieldDefinition beta = field("beta");
    FormIndexDefinition alphaIndex = index("alpha_index", alpha.fieldId());
    FormIndexDefinition betaIndex = index("beta_index", beta.fieldId());
    ApplicationFormDocument first = document(
        List.of(alpha, beta),
        List.of(alphaIndex, betaIndex)
    );
    ApplicationFormDocument reordered = document(
        List.of(beta, alpha),
        List.of(betaIndex, alphaIndex)
    );

    FormDocumentCodec.EncodedDocument firstEncoded = codec.encode(first);
    FormDocumentCodec.EncodedDocument reorderedEncoded = codec.encode(reordered);

    assertThat(firstEncoded.json()).isNotEqualTo(reorderedEncoded.json());
    assertThat(firstEncoded.checksum()).isEqualTo(reorderedEncoded.checksum());
  }

  @Test
  void canonicalizesTypedAndJsonDefaultValues() {
    ApplicationFormDocument first = document(
        List.of(decimalField("1.00"), jsonField("{\"b\":2,\"a\":1.0}")),
        List.of()
    );
    ApplicationFormDocument equivalent = document(
        List.of(decimalField("1.0"), jsonField("{\"a\":1.00,\"b\":2}")),
        List.of()
    );

    assertThat(codec.encode(first).checksum()).isEqualTo(codec.encode(equivalent).checksum());
  }


  @Test
  void legacySnapshotsOmitNewMetadataAndTranslationsParticipateInCanonicalChecksums() throws Exception {
    var legacy = document(List.of(field("customer")), List.of());
    var encoded = codec.encode(legacy);
    assertThat(encoded.json()).doesNotContain("labelLocalization");
    var mapper = new ObjectMapper();
    var tree = mapper.valueToTree(legacy);
    var field = (com.fasterxml.jackson.databind.node.ObjectNode) tree.path("fields").get(0);
    field.set("labelLocalization", mapper.valueToTree(new org.simplepoint.api.locale.LocalizedText("customer", "en-US", java.util.Map.of("ja", "顧客", "zh-CN", "客户"))));
    var translated = mapper.treeToValue(tree, ApplicationFormDocument.class);
    var localized = codec.encode(translated);
    assertThat(localized.checksum()).isNotEqualTo(encoded.checksum());
    assertThat(mapper.readValue(localized.json(), ApplicationFormDocument.class)).isEqualTo(translated);
    assertThat(translated.fields().getFirst().storageCode()).isEqualTo("customer");
    assertThat(translated.fields().getFirst().labelLocalization().translations()).containsEntry("ja", "顧客");
    var invalid = (com.fasterxml.jackson.databind.node.ObjectNode) field.path("labelLocalization");
    invalid.put("defaultValue", "x".repeat(121));
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> mapper.treeToValue(tree, ApplicationFormDocument.class)).isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
  }

  private static ApplicationFormDocument document(
      final List<FormFieldDefinition> fields,
      final List<FormIndexDefinition> indexes
  ) {
    return new ApplicationFormDocument(
        ApplicationFormApiVersion.DOCUMENT_V1,
        fields,
        indexes,
        List.of(),
        List.of()
    );
  }

  private static FormFieldDefinition field(final String id) {
    return new FormFieldDefinition(
        id,
        id,
        id,
        id,
        FormFieldType.STRING,
        FormStorageMode.STORED,
        false,
        false,
        120,
        null,
        null,
        null,
        List.of()
    );
  }

  private static FormIndexDefinition index(final String id, final String fieldId) {
    return new FormIndexDefinition(id, "idx_" + id, false, List.of(fieldId));
  }

  private static FormFieldDefinition decimalField(final String defaultValue) {
    return new FormFieldDefinition(
        "amount",
        "amount",
        "Amount",
        "Amount",
        FormFieldType.DECIMAL,
        FormStorageMode.STORED,
        false,
        false,
        null,
        18,
        2,
        new FormValue(FormValueKind.DECIMAL, defaultValue),
        List.of()
    );
  }

  private static FormFieldDefinition jsonField(final String defaultValue) {
    return new FormFieldDefinition(
        "payload",
        "payload",
        "Payload",
        "Payload",
        FormFieldType.JSON,
        FormStorageMode.STORED,
        false,
        false,
        null,
        null,
        null,
        new FormValue(FormValueKind.JSON, defaultValue),
        List.of()
    );
  }
}
