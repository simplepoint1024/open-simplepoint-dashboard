package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.model.*;

class FormFieldValidationsTest {
  private FormFieldDefinition field(FormFieldValidation rule) {
    return new FormFieldDefinition("name", "name", "名称", "名称", FormFieldType.STRING,
        FormStorageMode.STORED, true, false, 32, null, null, null, List.of(), null, rule);
  }

  @Test void unicodeLengthsAndOptionalEmptyValues() {
    var field = field(new FormFieldValidation(2, 3, null, null));
    FormFieldValidations.definition(field);
    assertThatCode(() -> FormFieldValidations.value(field, "😀日")).doesNotThrowAnyException();
    assertThatThrownBy(() -> FormFieldValidations.value(field, "😀")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FormFieldValidations.value(field, "汉字日本")).isInstanceOf(IllegalArgumentException.class);
    assertThatCode(() -> FormFieldValidations.value(field, null)).doesNotThrowAnyException();
  }

  @Test void patternCannotBypassRecordCompilation() {
    var field = field(new FormFieldValidation(2, 10, "^[A-Z]{2}-[0-9]+$", "编号格式为 AB-123"));
    var document = new ApplicationFormDocument("1", List.of(field), List.of(), List.of(), List.of());
    var compiler = new FormRecordPayloadCompiler();
    assertThatThrownBy(() -> compiler.compile(document, null, java.util.Map.of("name", "bad"), null, FormRecordState.DRAFT))
        .isInstanceOf(org.simplepoint.plugin.app.form.api.error.ApplicationFormException.class);
    assertThat(compiler.compile(document, null, java.util.Map.of("name", "AB-123"), null, FormRecordState.DRAFT)).containsEntry("name", "AB-123");
  }

  @Test void immutableDocumentRetainsRulesAndIncludesThemInItsChecksum() {
    var codec = new FormDocumentCodec(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
    var rule = new FormFieldValidation(2, 10, "^中.*$", "必须以中开头");
    var document = new ApplicationFormDocument("1", List.of(field(rule)), List.of(), List.of(), List.of());
    var encoded = codec.encode(document);
    assertThat(codec.decode(encoded.json()).fields().getFirst().validation()).isEqualTo(rule);
    var without = new ApplicationFormDocument("1", List.of(field(null)), List.of(), List.of(), List.of());
    assertThat(encoded.checksum()).isNotEqualTo(codec.encode(without).checksum());
    assertThat(codec.encode(without).json()).doesNotContain("validation");
  }

  @Test void rejectsInvalidBoundsAndNonPortablePatterns() {
    assertThatThrownBy(() -> FormFieldValidations.definition(field(new FormFieldValidation(5, 2, null, null)))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FormFieldValidations.definition(field(new FormFieldValidation(null, 33, null, null)))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FormFieldValidations.definition(field(new FormFieldValidation(null, null, "(?=a)", "提示")))).isInstanceOf(IllegalArgumentException.class);
    assertThatCode(() -> FormFieldValidations.value(field(new FormFieldValidation(null, null, "^(a+)+$", null)), "a".repeat(10000) + "!"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
