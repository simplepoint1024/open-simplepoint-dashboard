package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.*;

class FormCalculationInputValidationTest {
  private final FormRecordPayloadCompiler payloads = new FormRecordPayloadCompiler();

  @Test
  void defersComputedConditionsAndOldOutputDomainsButStillRejectsForgedOutputsAndOrdinaryRequiredInputs() {
    var document = document(false, null);
    assertThat(payloads.compile(document, Map.of("score", 99), Map.of("base", 1), FormRecordState.DRAFT, FormRecordState.SUBMITTED))
        .containsEntry("stored_base", 1).doesNotContainKeys("stored_score", "score");
    assertThat(payloads.compile(document, Map.of("score", 0), Map.of("base", 2), FormRecordState.DRAFT, FormRecordState.SUBMITTED))
        .containsEntry("stored_base", 2);
    assertThatThrownBy(() -> payloads.compile(document, Map.of(), Map.of("score", 1), null, FormRecordState.SUBMITTED))
        .isInstanceOf(ApplicationFormException.class);
    var required = document(true, null);
    assertThatThrownBy(() -> payloads.compile(required, Map.of("score", 99), Map.of(), null, FormRecordState.SUBMITTED))
        .isInstanceOf(ApplicationFormException.class);
    var createOnly = document(false, FormFieldWritePolicy.CREATE_ONLY);
    assertThatThrownBy(() -> payloads.compile(createOnly, Map.of(), Map.of("base", 1), null, FormRecordState.DRAFT))
        .isInstanceOf(ApplicationFormException.class);
    // The frozen post-calculation definition still carries the condition and the rating domain.
    assertThat(FormCalculationValidationProjection.compile(document)).hasSize(2);
  }

  @Test
  void aggregateInputChecksDeferComputedTotalsButRetainCollectionBounds() {
    var fields = document(false, null).fields();
    var detail = new FormDetailDefinition("lines", "lines", "Lines", List.of(fields.getFirst()), List.of());
    var count = new FormValidationConstraint("count", FormValidationConstraint.Kind.DETAIL_COUNT,
        null, null, "lines", null, "1", "40", "One line required");
    var sum = new FormValidationConstraint("sum", FormValidationConstraint.Kind.DETAIL_SUM,
        "score", null, "lines", "base", null, null, "Totals must match");
    var document = new ApplicationFormDocument("1", fields, List.of(), List.of(), List.of(), List.of(detail), List.of(count, sum));
    var rows = Map.of("lines", List.<Map<String, Object>>of(Map.of("base", 1)));
    FormAggregateValidation.validateInputs(document, Map.of("score", 999), rows);
    assertThatThrownBy(() -> FormAggregateValidation.validate(document, Map.of("score", 999), rows))
        .isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(() -> FormAggregateValidation.validateInputs(document, Map.of("score", 999), Map.of("lines", List.of())))
        .isInstanceOf(ApplicationFormException.class);
  }

  private static ApplicationFormDocument document(boolean baseRequired, FormFieldWritePolicy notePolicy) {
    var base = new FormFieldDefinition("base", "stored_base", "Base", "Base", FormFieldType.INT32,
        FormStorageMode.STORED, baseRequired, false, null, null, null, null, List.of());
    var score = new FormFieldDefinition("score", "stored_score", "Score", "Score", FormFieldType.INT32,
        FormStorageMode.COMPUTED, false, false, null, null, null, null, List.of(), null, null, null, new FormCalculation("1", "[base]*2"));
    var note = new FormFieldDefinition("note", "stored_note", "Note", "Note", FormFieldType.STRING,
        FormStorageMode.STORED, notePolicy != null, false, 128, null, null, null, List.of(), notePolicy);
    var rating = new FormComponentDefinition("rating", "field.rating", "1", "score", 24, null, null, null, null, false,
        Map.of("count", new FormValue(FormValueKind.INTEGER, "5")), List.of());
    var input = new FormComponentDefinition("note", "field.text", "1", "note", 24, null, null, null, null, false, Map.of(), List.of());
    var root = new FormComponentDefinition("root", "layout.form", "1", null, 24, null, null, null, null, false,
        Map.of(), List.of(rating, input));
    return new ApplicationFormDocument("1", List.of(base, score, note), List.of(),
        List.of(new FormSceneDefinition("main", "main", "Main", FormSceneType.MAIN, null, root)),
        List.of(new FormRuleDefinition("conditional-note", "note", FormRuleEffect.REQUIRE,
            new FormCondition(FormConditionOperator.GREATER_THAN, "score", List.of(new FormValue(FormValueKind.INTEGER, "2")), List.of()))));
  }
}
