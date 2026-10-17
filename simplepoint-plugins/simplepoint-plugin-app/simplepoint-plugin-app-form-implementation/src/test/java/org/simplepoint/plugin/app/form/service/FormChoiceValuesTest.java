package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormChoiceOption;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;

class FormChoiceValuesTest {

  private final FormDocumentValidator validator = new FormDocumentValidator(
      new DefaultFormComponentRegistry()
  );

  @Test
  void scalarChoicesPreservePrecisionAndRejectUnsupportedValues() {
    assertThat(FormChoiceValues.wireValue(FormFieldType.BOOLEAN, "false")).isEqualTo(false);
    assertThat(FormChoiceValues.wireValue(FormFieldType.INT32, "12.00")).isEqualTo(12);
    assertThat(FormChoiceValues.wireValue(FormFieldType.INT64, "9223372036854775807"))
        .isEqualTo("9223372036854775807");
    assertThat(FormChoiceValues.wireValue(FormFieldType.DECIMAL, "9999999999999999999.123456780"))
        .isEqualTo("9999999999999999999.12345678");
    assertThat(FormChoiceValues.canonical(FormFieldType.STRING, "日本語")).isEqualTo("日本語");
    assertThat(FormChoiceValues.canonical(FormFieldType.TIME, "12:00:00")).isEqualTo("12:00");
    assertThatThrownBy(() -> FormChoiceValues.canonical(FormFieldType.INT64, "9223372036854775808"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FormChoiceValues.canonical(FormFieldType.DATE, "2026-02-31"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FormChoiceValues.canonical(FormFieldType.BOOLEAN, "yes"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FormChoiceValues.canonical(FormFieldType.DECIMAL, "1e999999999"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void documentRejectsSemanticDuplicatesInvalidChoiceTypesAndOutsideDefaults() {
    validator.validate(document(field(List.of("1.20", "2.50"), "1.2")));
    assertInvalid(field(List.of("1.20", "1.2"), null));
    assertInvalid(field(List.of("1.20"), "2.50"));
    assertInvalid(field(List.of("not-a-number"), null));
    assertInvalid(field(List.of("0.123456789"), null));
    assertInvalid(field(List.of("999999999999999999999999999999"), null));
  }

  @Test
  void recordsUseTheSameExactWireValuesAsSelectSchemasAndKeepMaskedValues() {
    var field = field(List.of("1.20", "2.50"), null);
    assertThat(FormChoiceValues.displayValue(field, new BigDecimal("1.2000"))).isEqualTo("1.2");
    assertThat(FormChoiceValues.displayValue(field, "***")).isEqualTo("***");
    assertThat(FormChoiceValues.displayValue(field, "retired")).isEqualTo("retired");
    assertThat(FormChoiceValues.displayValue(field, null)).isNull();
  }

  private void assertInvalid(final FormFieldDefinition field) {
    assertThatThrownBy(() -> validator.validate(document(field)))
        .isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void plainNumericRecordsPreservePrecisionWithoutChoiceMetadata() {
    var decimal = field(List.of(), null);
    assertThat(FormChoiceValues.displayValue(decimal, new BigDecimal("12345678901234567890.12345678")))
        .isEqualTo("12345678901234567890.12345678");
    assertThat(FormChoiceValues.displayValue(decimal, "***")).isEqualTo("***");
    var integer = new FormFieldDefinition("serial", "serial", "Serial", "Serial", FormFieldType.INT64,
        FormStorageMode.STORED, false, false, null, null, null, null, List.of());
    assertThat(FormChoiceValues.displayValue(integer, Long.MAX_VALUE)).isEqualTo("9223372036854775807");
    assertThat(FormChoiceValues.displayValue(integer, Long.MIN_VALUE)).isEqualTo("-9223372036854775808");
    assertThat(FormChoiceValues.displayValue(integer, 12L)).isEqualTo("12");
    assertThat(FormChoiceValues.displayValue(integer, null)).isNull();
  }

  private static FormFieldDefinition field(final List<String> values, final String defaultValue) {
    return new FormFieldDefinition("amount", "amount", "Amount", "Amount", FormFieldType.DECIMAL,
        FormStorageMode.STORED, false, false, null, 30, 8,
        defaultValue == null ? null : new FormValue(FormValueKind.DECIMAL, defaultValue),
        values.stream().map(value -> new FormChoiceOption(value, value)).toList());
  }

  private static ApplicationFormDocument document(final FormFieldDefinition field) {
    var root = new FormComponentDefinition("root", "layout.form", "1", null, 24,
        null, null, null, null, false, Map.of(), List.of());
    return new ApplicationFormDocument("1", List.of(field), List.of(), List.of(
        new FormSceneDefinition("main", "main", "Main", FormSceneType.MAIN, null, root)
    ), List.of());
  }
}
