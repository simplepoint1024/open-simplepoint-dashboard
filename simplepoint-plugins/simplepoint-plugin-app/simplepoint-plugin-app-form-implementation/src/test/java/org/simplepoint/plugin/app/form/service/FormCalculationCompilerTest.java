package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.calculation.FormulaException;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormCalculation;
import org.simplepoint.plugin.app.form.api.model.FormChoiceOption;
import org.simplepoint.plugin.app.form.api.model.FormDetailDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormFieldValidation;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;

class FormCalculationCompilerTest {
  private final FormCalculationCompiler compiler =
      new FormCalculationCompiler(
          Clock.fixed(Instant.parse("2026-10-17T08:30:42.123456789Z"), ZoneOffset.UTC));

  @Test
  void computesExactDetailValuesAndTotalUsingStableIdentities() {
    var document =
        document(
            List.of(field("rate", null), field("total", "SUM([lines.amount])")),
            List.of(
                new FormDetailDefinition(
                    "lines",
                    "expense_lines",
                    "费用明细",
                    List.of(
                        field("price", null),
                        field("quantity", null),
                        field("amount", "ROUND([price]*[quantity]*[parent.rate],2)")),
                    List.of())));
    var plan = compiler.compile(document);
    var rows =
        List.of(
            Map.of("price", "10.01", "quantity", "3.00"),
            Map.of("price", "8.25", "quantity", "2.00"));
    var result = plan.evaluate(Map.of("rate", "0.95"), Map.of("lines", rows));
    assertThat(result.details().get("lines").getFirst()).containsEntry("amount", "28.53");
    assertThat(result.parent()).containsEntry("total", "44.21");
    assertThat(rows.getFirst()).doesNotContainKey("amount");
    assertThat(plan.dependencies().get("parent.total"))
        .containsExactlyInAnyOrder("lines.amount", "lines.price", "lines.quantity", "parent.rate");
    assertThat(plan.evaluate(Map.of("rate", "0.95"), Map.of("lines", List.of())).parent())
        .containsEntry("total", "0.00");
  }

  @Test
  void missingOptionalInputsAreNullAndClientsCannotForgeComputedResults() {
    var plan =
        compiler.compile(
            document(List.of(field("base", null), field("total", "[base]+1")), List.of()));
    assertThat(plan.evaluate(Map.of(), Map.of()).parent()).containsEntry("total", "1.00");
    assertFailure(() -> plan.evaluate(Map.of("total", "999.00"), Map.of()), "COMPUTED_WRITE");
    assertFailure(() -> plan.evaluate(Map.of("unknown", "1"), Map.of()), "UNKNOWN_FIELD");
  }

  @Test
  void rejectsUnknownDependenciesAndCyclesAcrossScopesBeforeAnyRowsExist() {
    assertFailure(
        () -> compiler.compile(document(List.of(field("total", "[missing]+1")), List.of())),
        "UNKNOWN_FIELD");
    assertFailure(
        () ->
            compiler.compile(
                document(
                    List.of(field("total", "SUM([lines.amount])")),
                    List.of(
                        new FormDetailDefinition(
                            "lines",
                            "lines",
                            "明细",
                            List.of(field("amount", "[parent.total]")),
                            List.of())))),
        "CIRCULAR_REFERENCE");
    assertFailure(
        () -> compiler.compile(document(List.of(field("total", "EVAL(\"secret\")")), List.of())),
        "UNKNOWN_FUNCTION");
  }

  @Test
  void refusesSilentPrecisionLossAndPreservesLongIntegers() {
    assertFailure(
        () ->
            compiler
                .compile(document(List.of(field("total", "1/3")), List.of()))
                .evaluate(Map.of(), Map.of()),
        "RESULT_RANGE");
    var rounded = compiler.compile(document(List.of(field("total", "ROUND(1/3,2)")), List.of()));
    assertThat(rounded.evaluate(Map.of(), Map.of()).parent()).containsEntry("total", "0.33");
    var integer = typed("serial", FormFieldType.INT64, "9223372036854775807");
    assertThat(
            compiler
                .compile(document(List.of(integer), List.of()))
                .evaluate(Map.of(), Map.of())
                .parent())
        .containsEntry("serial", "9223372036854775807");
    assertFailure(
        () ->
            compiler
                .compile(document(List.of(field("total", "999999999999999999")), List.of()))
                .evaluate(Map.of(), Map.of()),
        "RESULT_RANGE");
  }

  @Test
  void adaptsCalendarDatesInstantsJsonAndUnicodeWithoutChangingTheirMeaning() {
    var plan =
        compiler.compile(
            document(
                List.of(
                    typed("day", FormFieldType.DATE, "DATE(2026,10,17)"),
                    typed("at", FormFieldType.DATETIME, "NOW()"),
                    typed("label", FormFieldType.STRING, "UPPER(\"申請😀\")"),
                    typed("parts", FormFieldType.JSON, "\"[\"\"東京\"\",\"\"上海\"\"]\"")),
                List.of()));
    assertThat(plan.evaluate(Map.of(), Map.of()).parent())
        .containsEntry("day", "2026-10-17")
        .containsEntry("at", "2026-10-17T08:30:42.123456Z")
        .containsEntry("label", "申請😀")
        .containsEntry("parts", "[\"東京\",\"上海\"]");
  }

  @Test
  void formulaMetadataSurvivesSnapshotsAndAffectsChecksumsWithoutChangingLegacyJson()
      throws Exception {
    var codec = new FormDocumentCodec(new ObjectMapper());
    var original = document(List.of(field("base", null)), List.of());
    var legacy = codec.encode(original);
    assertThat(legacy.json()).doesNotContain("calculation");
    assertThat(codec.encode(codec.decode(legacy.json())).checksum()).isEqualTo(legacy.checksum());
    var calculated = document(List.of(field("base", null), field("total", "[base]*2")), List.of());
    var encoded = codec.encode(calculated);
    assertThat(codec.decode(encoded.json()).fields().get(1).calculation())
        .isEqualTo(new FormCalculation("1", "[base]*2"));
    assertThat(codec.encode(codec.decode(encoded.json())).checksum()).isEqualTo(encoded.checksum());
    assertThat(
            codec
                .encode(
                    document(List.of(field("base", null), field("total", "[base]*3")), List.of()))
                .checksum())
        .isNotEqualTo(encoded.checksum());
    assertThat(calculated.fields().get(1).allowsWrite(null)).isFalse();
  }

  @Test
  void validatesRequiredValuesUnicodeLengthsPatternsAndChoicesBeforeDependentsRun() {
    var required =
        configured(typed("required", FormFieldType.STRING, "NULL"), true, null, List.of());
    assertFailure(
        () -> compiler.compile(document(List.of(required), List.of())).evaluate(Map.of(), Map.of()),
        "RESULT_REQUIRED");
    var text =
        configured(
            typed("label", FormFieldType.STRING, "\"申請😀\""),
            false,
            new FormFieldValidation(3, 3, "^申請😀$", "Invalid label"),
            List.of());
    assertThat(
            compiler
                .compile(document(List.of(text), List.of()))
                .evaluate(Map.of(), Map.of())
                .parent())
        .containsEntry("label", "申請😀");
    var invalidText = configured(text, false, new FormFieldValidation(1, 2, null, null), List.of());
    assertFailure(
        () ->
            compiler
                .compile(document(List.of(invalidText), List.of()))
                .evaluate(Map.of(), Map.of()),
        "RESULT_TYPE");
    var choice =
        configured(field("choice", "2"), false, null, List.of(new FormChoiceOption("1.00", "One")));
    // IFERROR in a consumer cannot bypass the producer's declared field constraints.
    assertFailure(
        () ->
            compiler
                .compile(document(List.of(choice, field("next", "IFERROR([choice],0)")), List.of()))
                .evaluate(Map.of(), Map.of()),
        "RESULT_CHOICE");
  }

  @Test
  void rejectsLossyTemporalPrecisionAndFloatingPointSourceValues() {
    var time = typed("time", FormFieldType.TIME, "\"09:20:30.123456\"");
    assertThat(
            compiler
                .compile(document(List.of(time), List.of()))
                .evaluate(Map.of(), Map.of())
                .parent())
        .containsEntry("time", "09:20:30.123456");
    var nanos = typed("time", FormFieldType.TIME, "\"09:20:30.123456789\"");
    assertFailure(
        () -> compiler.compile(document(List.of(nanos), List.of())).evaluate(Map.of(), Map.of()),
        "RESULT_RANGE");
    var plan =
        compiler.compile(
            document(List.of(field("source", null), field("total", "[source]")), List.of()));
    assertFailure(() -> plan.evaluate(Map.of("source", 0.1d), Map.of()), "RESULT_TYPE");
    assertThat(plan.evaluate(Map.of("source", new java.math.BigDecimal("0.10")), Map.of()).parent())
        .containsEntry("total", "0.10");
  }

  @Test
  void boundsAggregateInputsAndUsesIndependentStableScopesEvenWhenFieldNamesMatch() {
    var plan =
        compiler.compile(
            document(
                List.of(field("amount", "SUM([lines.amount])")),
                List.of(
                    new FormDetailDefinition(
                        "lines",
                        "lines",
                        "Lines",
                        List.of(field("amount", "ROUND([base]*2,2)"), field("base", null)),
                        List.of()))));
    assertThat(plan.evaluate(Map.of(), Map.of("lines", List.of(Map.of("base", "2.00")))).parent())
        .containsEntry("amount", "4.00");
    assertFailure(
        () ->
            plan.evaluate(Map.of(), Map.of("lines", List.of(Map.of("base", "2", "amount", "999")))),
        "COMPUTED_WRITE");
    assertFailure(
        () ->
            plan.evaluate(Map.of(), Map.of("lines", java.util.Collections.nCopies(201, Map.of()))),
        "VALUE_SIZE");
    assertFailure(() -> plan.evaluate(Map.of(), Map.of()), "UNKNOWN_COLLECTION");
  }

  private static FormFieldDefinition configured(
      FormFieldDefinition source,
      boolean required,
      FormFieldValidation validation,
      List<FormChoiceOption> choices) {
    return new FormFieldDefinition(
        source.fieldId(),
        source.storageCode(),
        source.label(),
        source.storageLabel(),
        source.type(),
        source.storageMode(),
        source.submitRequired(),
        required,
        source.length(),
        source.precision(),
        source.scale(),
        source.defaultValue(),
        choices,
        source.writePolicy(),
        validation,
        source.access(),
        source.calculation());
  }

  private static void assertFailure(Runnable action, String code) {
    assertThatThrownBy(action::run)
        .isInstanceOf(ApplicationFormException.class)
        .cause()
        .isInstanceOf(FormulaException.class)
        .hasMessage(code);
  }

  private static FormFieldDefinition field(String id, String expression) {
    return typed(id, FormFieldType.DECIMAL, expression);
  }

  private static FormFieldDefinition typed(String id, FormFieldType type, String expression) {
    return new FormFieldDefinition(
        id,
        "column_" + id,
        id,
        id,
        type,
        expression == null ? FormStorageMode.STORED : FormStorageMode.COMPUTED,
        false,
        false,
        type == FormFieldType.STRING ? 120 : null,
        type == FormFieldType.DECIMAL ? 18 : null,
        type == FormFieldType.DECIMAL ? 2 : null,
        null,
        List.of(),
        null,
        null,
        null,
        expression == null ? null : new FormCalculation("1", expression));
  }

  private static ApplicationFormDocument document(
      List<FormFieldDefinition> fields, List<FormDetailDefinition> details) {
    return new ApplicationFormDocument("1", fields, List.of(), List.of(), List.of(), details);
  }
}
