package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormCalculation;
import org.simplepoint.plugin.app.form.api.model.FormChoiceOption;
import org.simplepoint.plugin.app.form.api.model.FormDetailDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormFieldValidation;
import org.simplepoint.plugin.app.form.api.model.FormIndexDefinition;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.entity.ApplicationFormVersionEntity;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;

class FormCalculationPublicationProjectionTest {
  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
  private final FormCalculationPublicationProjection projection = projection();

  static FormCalculationPublicationProjection projection() {
    return new FormCalculationPublicationProjection(new FormDocumentCodec(MAPPER),
        new FormStorageProjectionCompiler(MAPPER), MAPPER, Clock.systemUTC());
  }

  @Test
  void projectsStableMainAndOwnedIdentitiesAndNeutralConstraintsFromTheVersion() throws Exception {
    var version = version(document());
    var definition = projection.compile(version, "model-a", false);
    assertThat(definition.formulas()).containsExactlyEntriesOf(Map.of("total", "SUM([lines.amount])"));
    var detail = definition.details().get("lines");
    assertThat(detail.physicalModelId()).isEqualTo("model-lines");
    assertThat(detail.schemaReference().version()).isEqualTo(7);
    assertThat(detail.schemaReference().checksum()).isEqualTo(new FormStorageProjectionCompiler(MAPPER)
        .compileDetail(document().details().getFirst(), "model-a").checksum());
    assertThat(detail.formulas()).containsEntry("amount", "ROUND([price]*[quantity],2)");
    assertThat(detail.constraints().get("note").pattern()).isEqualTo("^[\\p{L}😀]+$");
    assertThat(detail.constraints().get("note").minLength()).isEqualTo(1);
    assertThat(definition.constraints().get("rate").choices()).containsExactly(new LogicalValue(LogicalValueKind.DECIMAL, "0.95"));
    String json = MAPPER.writeValueAsString(definition);
    assertThat(json).doesNotContain("UI-only validation hint", "Display choice", "renamed_", "authorization");
    assertThat(MAPPER.readValue(json, RecordCalculationDefinition.class)).isEqualTo(definition);
  }

  @Test
  void preservesAbsenceForOrdinaryVersionsButExplicitlyRemovesInstalledRules() {
    var plain = version(new ApplicationFormDocument("1", List.of(), List.of(), List.of(), List.of()));
    String before = plain.getDocumentJson();
    assertThat(projection.compile(plain, "model-a", false)).isNull();
    var removed = projection.compile(plain, "model-a", true);
    assertThat(removed.formulas()).isEmpty();
    assertThat(removed.stateTransitions().transitions()).containsEntry("SUBMITTED", List.of("SUBMITTED"));
    assertThat(plain.getDocumentJson()).isEqualTo(before).doesNotContain("calculation");
    var constrained = version(new ApplicationFormDocument("1", List.of(choice("rate")), List.of(), List.of(), List.of()));
    assertThat(projection.compile(constrained, "model-a", false)).isNull();
    assertThat(projection.compile(constrained, "model-a", true).constraints()).containsKey("rate");
  }

  @Test
  void includesCalculatedColumnsAndIndexesWithoutMaterializingLegacyPlaceholders() {
    var total = number("total", "1+1");
    var legacy = new FormFieldDefinition("legacy", null, "Legacy", null, FormFieldType.STRING, FormStorageMode.COMPUTED,
        false, false, null, null, null, null, List.of());
    var document = new ApplicationFormDocument("1", List.of(total, legacy),
        List.of(new FormIndexDefinition("index-total", "idx_total", false, List.of("total"))), List.of(), List.of());
    var storage = new FormStorageProjectionCompiler(MAPPER).compile(document);
    assertThat(storage.schema().fields()).extracting(field -> field.fieldId()).contains("total").doesNotContain("legacy");
    assertThat(storage.schema().indexes().getFirst().fieldCodes()).containsExactly("renamed_total");
    assertThat(projection.compile(version(document), "model-a", false).formulas()).containsOnlyKeys("total");
  }

  @Test
  void rejectsMissingDuplicateExtraAndWrongOwnerDetailBindings() throws Exception {
    var original = version(document());
    var binding = FormDetailSchemaCoordinator.decode(original.getDetailBindingsJson(), MAPPER).getFirst();
    for (var bindings : List.of(List.<FormDetailBinding>of(), List.of(binding, binding),
        List.of(binding, new FormDetailBinding("extra", "model-extra", 7, binding.checksum())),
        List.of(new FormDetailBinding("lines", "model-a", 7, binding.checksum())),
        List.of(new FormDetailBinding("lines", "model-lines", 7, "f".repeat(64))))) {
      var version = version(document());
      version.setDetailBindingsJson(MAPPER.writeValueAsString(bindings));
      assertPersistenceFailure(version);
    }
    var version = version(document());
    version.setDetailBindingsJson(null);
    assertPersistenceFailure(version);
  }

  @Test
  void rejectsTamperedDocumentOrStorageAndUnreadySchemas() {
    var document = version(document());
    document.setChecksum("f".repeat(64));
    assertPersistenceFailure(document);
    var checksum = version(document());
    checksum.setStorageChecksum("f".repeat(64));
    assertPersistenceFailure(checksum);
    var schema = version(document());
    schema.setStorageSchemaJson(new FormStorageProjectionCompiler(MAPPER)
        .compile(new ApplicationFormDocument("1", List.of(), List.of(), List.of(), List.of())).json());
    assertPersistenceFailure(schema);
    var pending = version(document());
    pending.setPhysicalModelVersion(null);
    assertPersistenceFailure(pending);
  }

  @Test
  void preservesScalarChoiceTypesWithoutLosingIntegerDecimalOrTemporalPrecision() {
    var values = Map.ofEntries(Map.entry(FormFieldType.STRING, "中文・日本語😀"), Map.entry(FormFieldType.TEXT, "多行\n文字"),
        Map.entry(FormFieldType.BOOLEAN, "false"), Map.entry(FormFieldType.INT32, "2147483647"),
        Map.entry(FormFieldType.INT64, "9223372036854775807"), Map.entry(FormFieldType.DECIMAL, "9999999999999999.99"),
        Map.entry(FormFieldType.DATE, "2026-10-18"), Map.entry(FormFieldType.TIME, "12:34:56.123456"),
        Map.entry(FormFieldType.DATETIME, "2026-10-18T01:02:03.123456Z"),
        Map.entry(FormFieldType.UUID, "01328c7a-7423-4b1a-8d66-9c874acb49c9"));
    var fields = values.entrySet().stream().map(entry -> new FormFieldDefinition(entry.getKey().name(),
        "choice_" + entry.getKey().name().toLowerCase(java.util.Locale.ROOT), "Choice", "Choice", entry.getKey(), FormStorageMode.STORED,
        false, false, entry.getKey() == FormFieldType.STRING ? 100 : null,
        entry.getKey() == FormFieldType.DECIMAL ? 18 : null, entry.getKey() == FormFieldType.DECIMAL ? 2 : null,
        null, List.of(new FormChoiceOption(entry.getValue(), "UI label")))).toList();
    var definition = projection.compile(version(new ApplicationFormDocument("1", fields, List.of(), List.of(), List.of())), "model-a", true);
    for (var entry : values.entrySet()) {
      var choice = definition.constraints().get(entry.getKey().name()).choices().getFirst();
      assertThat(choice.canonicalValue()).isEqualTo(entry.getValue());
      var kind = switch (entry.getKey()) {
        case INT32, INT64 -> LogicalValueKind.INTEGER;
        case STRING, TEXT -> LogicalValueKind.STRING;
        default -> LogicalValueKind.valueOf(entry.getKey().name());
      };
      assertThat(choice.kind()).isEqualTo(kind);
    }
  }

  @Test
  void rejectsInvalidDependenciesBeforePreparingADnaIntent() {
    var document = new ApplicationFormDocument("1", List.of(number("total", "[missing]+1")), List.of(), List.of(), List.of());
    assertThatThrownBy(() -> projection.compile(version(document), "model-a", false))
        .isInstanceOfSatisfying(ApplicationFormException.class,
            failure -> assertThat(failure.errorCode()).isEqualTo(ApplicationFormErrorCode.DOCUMENT_INVALID));
  }

  private void assertPersistenceFailure(final ApplicationFormVersionEntity version) {
    assertThatThrownBy(() -> projection.compile(version, "model-a", false))
        .isInstanceOfSatisfying(ApplicationFormException.class,
            failure -> assertThat(failure.errorCode()).isEqualTo(ApplicationFormErrorCode.PERSISTENCE_FAILED));
  }

  static ApplicationFormDocument document() {
    var note = new FormFieldDefinition("note", "renamed_note", "Note", "Note", FormFieldType.STRING, FormStorageMode.STORED,
        false, false, 100, null, null, null, List.of(), null,
        new FormFieldValidation(1, 20, "^[\\p{L}😀]+$", "UI-only validation hint"));
    return new ApplicationFormDocument("1", List.of(number("total", "SUM([lines.amount])"), choice("rate")), List.of(), List.of(), List.of(),
        List.of(new FormDetailDefinition("lines", "expense_lines", "Details", List.of(number("price", null), number("quantity", null),
            number("amount", "ROUND([price]*[quantity],2)"), note), List.of())));
  }

  private static FormFieldDefinition choice(final String id) {
    return new FormFieldDefinition(id, "renamed_" + id, id, id, FormFieldType.DECIMAL, FormStorageMode.STORED,
        false, false, null, 18, 2, null, List.of(new FormChoiceOption("0.950", "Display choice")));
  }

  static FormFieldDefinition number(final String id, final String expression) {
    return new FormFieldDefinition(id, "renamed_" + id, id, id, FormFieldType.DECIMAL,
        expression == null ? FormStorageMode.STORED : FormStorageMode.COMPUTED, false, false, null, 18, 2, null, List.of(), null, null, null,
        expression == null ? null : new FormCalculation("1", expression));
  }

  static ApplicationFormVersionEntity version(final ApplicationFormDocument document) {
    var version = new ApplicationFormVersionEntity();
    populate(version, document);
    return version;
  }

  static void populate(final ApplicationFormVersionEntity version, final ApplicationFormDocument document) {
    var encoded = new FormDocumentCodec(MAPPER).encode(document);
    var compiler = new FormStorageProjectionCompiler(MAPPER);
    var storage = compiler.compile(document);
    version.setDocumentJson(encoded.json());
    version.setChecksum(encoded.checksum());
    version.setStorageSchemaJson(storage.json());
    version.setStorageChecksum(storage.checksum());
    version.setPhysicalModelVersion(3);
    try {
      version.setDetailBindingsJson(MAPPER.writeValueAsString(document.details().stream().map(detail ->
          new FormDetailBinding(detail.detailId(), "model-" + detail.detailId(), 7, compiler.compileDetail(detail, "model-a").checksum())).toList()));
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }
}
