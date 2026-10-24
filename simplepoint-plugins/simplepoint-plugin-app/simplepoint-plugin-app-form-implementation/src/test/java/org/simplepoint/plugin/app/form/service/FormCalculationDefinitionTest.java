package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormCalculation;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormDetailDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormIndexDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;

class FormCalculationDefinitionTest {
  private final FormDocumentValidator validator =
      new FormDocumentValidator(new DefaultFormComponentRegistry());

  @Test
  void requiresVersionedBoundedExpressions() {
    for (String version : List.of("", "0", "2")) {
      assertThatThrownBy(() -> new FormCalculation(version, "1"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (String expression : List.of("", " ", "1".repeat(8193))) {
      assertThatThrownBy(() -> new FormCalculation("1", expression))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new FormCalculation("1", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void computedDefinitionsHavePhysicalIdentityAndCanBeIndexedAndReusedAcrossScenes() {
    var total = field("total", "SUM([lines.amount])", true);
    var child =
        new FormDetailDefinition(
            "lines",
            "expense_lines",
            "明细",
            List.of(field("amount", "ROUND([base]*2,2)", false), field("base", null, false)),
            List.of(
                new FormIndexDefinition("amount-index", "amount_idx", false, List.of("amount"))));
    var main =
        scene(
            "main",
            FormSceneType.MAIN,
            component("main-root", null, List.of(component("total-main", "total", List.of()))));
    var dialog =
        scene(
            "details",
            FormSceneType.DIALOG,
            component("dialog-root", null, List.of(component("total-dialog", "total", List.of()))));
    var document =
        new ApplicationFormDocument(
            "1",
            List.of(total),
            List.of(new FormIndexDefinition("total-index", "total_idx", false, List.of("total"))),
            List.of(main, dialog),
            List.of(),
            List.of(child));
    validator.validate(document);
    assertThat(total.persistent()).isTrue();
    assertThat(total.allowsWrite(null)).isFalse();
  }

  @Test
  void rejectsFormulaOnOrdinaryFieldsDefaultsAndUnknownOrCircularReferences() {
    var calculated = field("total", "1", false);
    for (var mode : List.of(FormStorageMode.STORED, FormStorageMode.TRANSIENT)) {
      var invalid =
          new FormFieldDefinition(
              calculated.fieldId(),
              calculated.storageCode(),
              calculated.label(),
              calculated.storageLabel(),
              calculated.type(),
              mode,
              false,
              false,
              null,
              18,
              2,
              null,
              List.of(),
              null,
              null,
              null,
              calculated.calculation());
      assertInvalid(document(invalid));
    }
    var defaulted =
        new FormFieldDefinition(
            "total",
            "total",
            "Total",
            "Total",
            FormFieldType.DECIMAL,
            FormStorageMode.COMPUTED,
            false,
            false,
            null,
            18,
            2,
            new FormValue(FormValueKind.DECIMAL, "0"),
            List.of(),
            null,
            null,
            null,
            new FormCalculation("1", "1"));
    assertInvalid(document(defaulted));
    assertInvalid(document(field("total", "[missing]", false)));
    assertInvalid(document(field("total", "IF(FALSE,[total],1)", false)));
  }

  @Test
  void legacyComputedPlaceholdersRemainUnstoredAndCannotAcquireStorageIdentity() {
    var legacy =
        new FormFieldDefinition(
            "display",
            null,
            "Display",
            null,
            FormFieldType.DECIMAL,
            FormStorageMode.COMPUTED,
            false,
            false,
            null,
            18,
            2,
            null,
            List.of());
    validator.validate(document(legacy));
    assertThat(legacy.persistent()).isFalse();
    var wronglyStored =
        new FormFieldDefinition(
            "display",
            "display",
            "Display",
            "Display",
            FormFieldType.DECIMAL,
            FormStorageMode.COMPUTED,
            false,
            false,
            null,
            18,
            2,
            null,
            List.of());
    assertInvalid(document(wronglyStored));
  }

  @Test
  void validCalculationsUseTheStandardPublicationContractAndInvalidGraphsRemainRejected() {
    validator.validatePublication(document(field("total", "1", false)));
    validator.validatePublication(document(field("total", "1", true)));
    assertThatThrownBy(() -> validator.validatePublication(document(field("total", "[missing]", false))))
        .isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(() -> validator.validatePublication(document(field("total", "[total]+1", false))))
        .isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void calculatedTemporalChoicesMustRoundTripAtTheSamePrecisionAsOrdinaryFields() {
    for (var type : List.of(FormFieldType.TIME, FormFieldType.DATETIME)) {
      String prefix = type == FormFieldType.TIME ? "12:34:56." : "2026-10-18T12:34:56.";
      String suffix = type == FormFieldType.TIME ? "" : "Z";
      for (String fraction : List.of("123456", "1234567")) {
        var field = new FormFieldDefinition("clock", "column_clock", "Clock", "Clock", type, FormStorageMode.COMPUTED,
            false, false, null, null, null, null,
            List.of(new org.simplepoint.plugin.app.form.api.model.FormChoiceOption(prefix + fraction + suffix, "Time")),
            null, null, null, new FormCalculation("1", "NOW()"));
        if (fraction.length() == 6) {
          validator.validate(document(field));
        } else {
          assertInvalid(document(field));
        }
      }
    }
  }

  private void assertInvalid(ApplicationFormDocument document) {
    assertThatThrownBy(() -> validator.validate(document))
        .isInstanceOf(ApplicationFormException.class);
  }

  private static ApplicationFormDocument document(FormFieldDefinition field) {
    return new ApplicationFormDocument(
        "1",
        List.of(field),
        List.of(),
        List.of(scene("main", FormSceneType.MAIN, component("root", null, List.of()))),
        List.of());
  }

  private static FormFieldDefinition field(String id, String expression, boolean required) {
    return new FormFieldDefinition(
        id,
        "column_" + id,
        id,
        id,
        FormFieldType.DECIMAL,
        expression == null ? FormStorageMode.STORED : FormStorageMode.COMPUTED,
        false,
        required,
        null,
        18,
        2,
        null,
        List.of(),
        null,
        null,
        null,
        expression == null ? null : new FormCalculation("1", expression));
  }

  private static FormSceneDefinition scene(
      String id, FormSceneType type, FormComponentDefinition root) {
    return new FormSceneDefinition(id, id, id, type, type == FormSceneType.MAIN ? null : 640, root);
  }

  private static FormComponentDefinition component(
      String id, String field, List<FormComponentDefinition> children) {
    return new FormComponentDefinition(
        id,
        field == null ? "layout.form" : "field.number",
        "1",
        field,
        24,
        null,
        null,
        null,
        null,
        false,
        Map.of(),
        children);
  }
}
