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

/** Verifies APP data definitions compile to deterministic DNA storage contracts. */
class FormStorageProjectionCompilerTest {

  private final FormStorageProjectionCompiler compiler = new FormStorageProjectionCompiler(
      new ObjectMapper().findAndRegisterModules()
  );

  @Test
  void excludesTransientFieldsAndMapsIndexesByStableFieldIdentity() {
    FormFieldDefinition stored = stringField("customer_name", "Customer name", false, null);
    FormFieldDefinition transientField = new FormFieldDefinition(
        "display_hint",
        null,
        "Display hint",
        null,
        FormFieldType.TEXT,
        FormStorageMode.TRANSIENT,
        false,
        false,
        null,
        null,
        null,
        null,
        List.of()
    );
    ApplicationFormDocument document = document(
        List.of(transientField, stored),
        List.of(new FormIndexDefinition(
            "customer_name_index",
            "idx_customer_name",
            true,
            List.of(stored.fieldId())
        ))
    );

    var projection = compiler.compile(document);

    assertThat(projection.schema().fields()).hasSize(2);
    assertThat(projection.schema().fields().getFirst()).satisfies(field -> {
      assertThat(field.fieldId()).isEqualTo("platform_record_state");
      assertThat(field.code()).isEqualTo("record_state");
      assertThat(field.nullable()).isFalse();
      assertThat(field.defaultValue().canonicalValue()).isEqualTo("DRAFT");
    });
    assertThat(projection.schema().fields().get(1)).satisfies(field -> {
      assertThat(field.fieldId()).isEqualTo(stored.fieldId());
      assertThat(field.code()).isEqualTo(stored.storageCode());
      assertThat(field.nullable()).isTrue();
    });
    assertThat(projection.schema().indexes()).singleElement().satisfies(index ->
        assertThat(index.fieldCodes()).containsExactly(stored.storageCode()));
    assertThat(projection.fieldCodes())
        .containsExactlyEntriesOf(java.util.Map.of(stored.fieldId(), stored.storageCode()));
  }

  @Test
  void ignoresUiLabelsAndCanonicalizesDefaultsForStorageChecksum() {
    ApplicationFormDocument first = document(
        List.of(decimalField("Amount", "1.00"), stringField(
            "customer_name", "Customer name", false, null
        )),
        List.of()
    );
    ApplicationFormDocument equivalent = document(
        List.of(stringField("customer_name", "Legal name", false, null),
            decimalField("Total", "1.0")),
        List.of()
    );

    assertThat(compiler.compile(first).checksum())
        .isEqualTo(compiler.compile(equivalent).checksum());
  }

  @Test
  void decimalDefaultsRetainTheDeclaredDnaScaleIncludingZero() {
    for (String input : List.of("0", "0.00", "1.0", "-2.500")) {
      var schema = compiler.compile(document(List.of(decimalField("Amount", input)), List.of())).schema();
      var actual = schema.fields().stream().filter(field -> field.code().equals("amount")).findFirst().orElseThrow();
      assertThat(actual.defaultValue().canonicalValue())
          .isEqualTo(new java.math.BigDecimal(input).setScale(2).toPlainString());
    }
  }

  @Test
  void mapsStorageRequiredToNonNullableDnaField() {
    FormValue defaultValue = new FormValue(FormValueKind.STRING, "unknown");
    var projection = compiler.compile(document(
        List.of(stringField("customer_name", "Customer name", true, defaultValue)),
        List.of()
    ));

    assertThat(projection.schema().fields()).element(1)
        .satisfies(field -> {
          assertThat(field.nullable()).isFalse();
          assertThat(field.defaultValue().canonicalValue()).isEqualTo("unknown");
        });
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

  private static FormFieldDefinition stringField(
      final String id,
      final String label,
      final boolean storageRequired,
      final FormValue defaultValue
  ) {
    return new FormFieldDefinition(
        id,
        id,
        label,
        id,
        FormFieldType.STRING,
        FormStorageMode.STORED,
        true,
        storageRequired,
        120,
        null,
        null,
        defaultValue,
        List.of()
    );
  }

  private static FormFieldDefinition decimalField(
      final String label,
      final String defaultValue
  ) {
    return new FormFieldDefinition(
        "amount",
        "amount",
        label,
        "Amount",
        FormFieldType.DECIMAL,
        FormStorageMode.STORED,
        true,
        false,
        null,
        18,
        2,
        new FormValue(FormValueKind.DECIMAL, defaultValue),
        List.of()
    );
  }
}
