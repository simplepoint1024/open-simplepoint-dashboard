package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.validation.InvalidFormException;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;

class FormJsonContractTest {

  private final FormRecordPayloadCompiler compiler = new FormRecordPayloadCompiler();
  private final FormDocumentValidator validator = new FormDocumentValidator(new DefaultFormComponentRegistry());

  @Test
  void preservesTheExactWriteIntentAndSeparatesBlankFromJsonNull() {
    String input = " {\"z\":9007199254740993, \"a\":1.00} ";
    var document = document(false, null);
    assertThat(compiler.compile(document, Map.of(), Map.of("payload", input), null, FormRecordState.DRAFT))
        .containsEntry("payload", input);
    assertThat(compiler.compile(document, Map.of(), Map.of("payload", "null"), null, FormRecordState.DRAFT))
        .containsEntry("payload", "null");
    assertThat(compiler.compile(document, Map.of(), Map.of("payload", " "), null, FormRecordState.DRAFT))
        .containsEntry("payload", null);
    assertThat(compiler.compile(document, Map.of(), Map.of(), null, FormRecordState.DRAFT)).doesNotContainKey("payload");
  }

  @Test
  void requiredJsonUsesTheSameMissingValuesForSubmissionAndPublicationDefaults() {
    for (String missing : List.of("null", "{}", "[]", "\"\"", "\" \u3000\"")) {
      assertThatThrownBy(() -> compiler.compile(document(true, null), Map.of(), Map.of("payload", missing),
          null, FormRecordState.SUBMITTED)).isInstanceOf(ApplicationFormException.class);
      assertThatThrownBy(() -> validator.validatePublication(document(true, missing)))
          .isInstanceOf(InvalidFormException.class);
    }
    for (String present : List.of("0", "false", "\"[]\"", "[null]", "{\"a\":null}")) {
      assertThat(compiler.compile(document(true, null), Map.of(), Map.of("payload", present),
          null, FormRecordState.SUBMITTED)).containsEntry("payload", present);
      validator.validatePublication(document(true, present));
    }
  }

  @Test
  void rejectsAmbiguousOrNonTextValuesEvenForDrafts() {
    for (Object invalid : List.of("{\"a\":1,\"a\":2}", "{} true", Map.of("a", 1), List.of(1))) {
      assertThatThrownBy(() -> compiler.compile(document(false, null), Map.of(), Map.of("payload", invalid),
          null, FormRecordState.DRAFT)).isInstanceOf(ApplicationFormException.class);
    }
  }

  private static ApplicationFormDocument document(final boolean required, final String defaultValue) {
    var field = new FormFieldDefinition("payload", "payload", "Payload", "Payload", FormFieldType.JSON,
        FormStorageMode.STORED, required, false, null, null, null,
        defaultValue == null ? null : new FormValue(FormValueKind.JSON, defaultValue), List.of());
    var root = new FormComponentDefinition("root", "layout.form", "1", null, 24,
        null, null, null, null, false, Map.of(), List.of());
    return new ApplicationFormDocument("1", List.of(field), List.of(), List.of(
        new FormSceneDefinition("main", "main", "Main", FormSceneType.MAIN, null, root)), List.of());
  }
}
