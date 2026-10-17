package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormChoiceOption;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormCondition;
import org.simplepoint.plugin.app.form.api.model.FormConditionOperator;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;

class FormTemporalContractTest {

  private final FormRecordPayloadCompiler compiler = new FormRecordPayloadCompiler();

  @Test
  void acceptsDnaWireValuesAndEquivalentOffsetsWithoutChangingTheWriteIntent() {
    var field = field(FormFieldType.DATETIME, null, List.of());
    var values = Map.<String, Object>of("instant", "2026-10-14T01:02:03.123456Z");
    assertThat(compiler.compile(document(field), values, values, FormRecordState.DRAFT, FormRecordState.SUBMITTED))
        .containsEntry("instant", values.get("instant"));
    var equivalent = new FormCondition(FormConditionOperator.EQUALS, "instant",
        List.of(new FormValue(FormValueKind.DATETIME, "2026-10-14T09:02:03.123456+08:00")), List.of());
    assertThat(FormRuleEvaluator.matches(equivalent, values)).isTrue();
    var threshold = new FormCondition(FormConditionOperator.LESS_THAN, "instant",
        List.of(new FormValue(FormValueKind.DATETIME, "2026-10-14T01:02:03.123456001Z")), List.of());
    assertThat(FormRuleEvaluator.matches(threshold, values)).isTrue();
    assertThat(FormChoiceValues.displayValue(field, values.get("instant"))).isEqualTo(values.get("instant"));
  }

  @Test
  void rejectsInvalidOrLossyWritesBeforeDnaEvenForDrafts() {
    Map<FormFieldType, List<String>> invalid = Map.of(
        FormFieldType.DATE, List.of("2023-02-29", "2024-02-29T00:00:00Z"),
        FormFieldType.TIME, List.of("24:01:00", "12:34:56.000000001"),
        FormFieldType.DATETIME, List.of("2026-10-14 01:02:03", "2026-10-14T01:02:03.123456001Z"));
    invalid.forEach((type, values) -> values.forEach(value -> assertThatThrownBy(() -> compiler.compile(
        document(field(type, null, List.of())), Map.of(), Map.of("instant", value), null, FormRecordState.DRAFT))
        .isInstanceOfSatisfying(ApplicationFormException.class, failure -> {
          assertThat(failure.errorCode()).isEqualTo(ApplicationFormErrorCode.RECORD_INVALID);
          assertThat(failure.getMessage()).doesNotContain(value);
        })));
  }

  @Test
  void validatesPersistentDefaultsAndChoicesAgainstTheSamePrecision() {
    var validator = new FormDocumentValidator(new DefaultFormComponentRegistry(List.of()));
    var invalidDefault = field(FormFieldType.TIME, new FormValue(FormValueKind.TIME, "12:34:56.123456789"), List.of());
    var invalidChoice = field(FormFieldType.DATETIME, null,
        List.of(new FormChoiceOption("2026-10-14T01:02:03.123456789Z", "Instant")));
    assertThatThrownBy(() -> validator.validate(document(invalidDefault))).isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(() -> validator.validate(document(invalidChoice))).isInstanceOf(ApplicationFormException.class);
    validator.validate(document(field(FormFieldType.TIME, new FormValue(FormValueKind.TIME, "00:00:00.000001"), List.of())));
  }

  private static FormFieldDefinition field(final FormFieldType type, final FormValue value, final List<FormChoiceOption> choices) {
    return new FormFieldDefinition("instant", "instant", "Instant", "Instant", type, FormStorageMode.STORED,
        false, false, null, null, null, value, choices);
  }

  private static ApplicationFormDocument document(final FormFieldDefinition field) {
    var root = new FormComponentDefinition("root", "layout.form", "1", null, 24,
        null, null, null, null, false, Map.of(), List.of());
    return new ApplicationFormDocument("1", List.of(field), List.of(), List.of(
        new FormSceneDefinition("main", "main", "Main", FormSceneType.MAIN, null, root)), List.of());
  }
}
