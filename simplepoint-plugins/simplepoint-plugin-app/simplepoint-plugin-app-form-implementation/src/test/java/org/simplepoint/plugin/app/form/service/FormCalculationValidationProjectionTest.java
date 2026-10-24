package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormCondition;
import org.simplepoint.plugin.app.form.api.model.FormConditionOperator;
import org.simplepoint.plugin.app.form.api.model.FormDetailDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormRuleDefinition;
import org.simplepoint.plugin.app.form.api.model.FormRuleEffect;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValidationConstraint;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationCondition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationField;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationRule;

class FormCalculationValidationProjectionTest {
  @Test
  void freezesCalculatedConditionsRequiredOutputsAndAllAggregateKindsWithoutUiOrStorageCoupling() throws Exception {
    var total = required(FormCalculationPublicationProjectionTest.number("total", "SUM([lines.amount])"));
    var amount = required(FormCalculationPublicationProjectionTest.number("amount", "[price]*2"));
    var note = plain("note", FormFieldType.STRING);
    var start = plain("start", FormFieldType.DATE);
    var end = plain("end", FormFieldType.DATE);
    var detail = new FormDetailDefinition("lines", "renamed_lines", "明细", List.of(amount,
        FormCalculationPublicationProjectionTest.number("price", null)), List.of());
    var condition = new FormCondition(FormConditionOperator.GREATER_THAN, "total",
        List.of(new FormValue(FormValueKind.DECIMAL, "100.005")), List.of());
    var component = new FormComponentDefinition("note-component", "field.text", "1", "note", 24, null, null, null, null, false, Map.of(), List.of());
    var scene = new FormSceneDefinition("main", "main", "主表", FormSceneType.MAIN, null, component);
    var constraints = List.of(
        new FormValidationConstraint("total-range", FormValidationConstraint.Kind.RANGE, "total", null, null, null, "0", "1000", "UI-only bound"),
        new FormValidationConstraint("dates", FormValidationConstraint.Kind.AFTER, "end", "start", null, null, null, null, "UI-only dates"),
        new FormValidationConstraint("count", FormValidationConstraint.Kind.DETAIL_COUNT, null, null, "lines", null, "1", "40", "UI-only count"),
        new FormValidationConstraint("row-range", FormValidationConstraint.Kind.DETAIL_RANGE, null, null, "lines", "amount", "0", "1000", "UI-only amount"),
        new FormValidationConstraint("sum", FormValidationConstraint.Kind.DETAIL_SUM, "total", null, "lines", "amount", null, null, "UI-only total"));
    var document = new ApplicationFormDocument("1", List.of(total, note, start, end), List.of(), List.of(scene),
        List.of(new FormRuleDefinition("require-note", "note-component", FormRuleEffect.REQUIRE, condition)), List.of(detail), constraints);
    var version = FormCalculationPublicationProjectionTest.version(document);
    String original = version.getDocumentJson();
    var definition = FormCalculationPublicationProjectionTest.projection().compile(version, "model-a", false);
    assertThat(version.getDocumentJson()).isEqualTo(original);
    var rules = definition.validationsOrEmpty();
    assertThat(definition.constraints().get("platform_record_state").choices()).extracting(value -> value.canonicalValue())
        .containsExactly("DRAFT", "SUBMITTED");
    assertThat(rules).hasSize(8);
    var rootRequired = rules.stream().filter(rule -> rule.kind() == RecordValidationRule.Kind.REQUIRED
        && rule.field().equals(new RecordValidationField("parent", "total"))).toList();
    assertThat(rootRequired).hasSize(1);
    assertThat(rootRequired.getFirst().when().values().getFirst().canonicalValue()).isEqualTo("SUBMITTED");
    assertThat(rules).anySatisfy(rule -> {
      assertThat(rule.field()).isEqualTo(new RecordValidationField("lines", "amount"));
      assertThat(rule.kind()).isEqualTo(RecordValidationRule.Kind.REQUIRED);
      assertThat(rule.when().field()).isEqualTo(new RecordValidationField("parent", "platform_record_state"));
    });
    assertThat(rules.stream().filter(rule -> rule.kind() == RecordValidationRule.Kind.REQUIRED && rule.field().fieldId().equals("note"))
        .flatMap(rule -> leaves(rule.when()).stream())).anySatisfy(leaf -> {
          assertThat(leaf.field()).isEqualTo(new RecordValidationField("parent", "total"));
          assertThat(leaf.values().getFirst().canonicalValue()).isEqualTo("100.005");
        });
    assertThat(rules).extracting(RecordValidationRule::kind).contains(RecordValidationRule.Kind.AFTER,
        RecordValidationRule.Kind.DETAIL_COUNT, RecordValidationRule.Kind.DETAIL_SUM, RecordValidationRule.Kind.RANGE);
    String json = new ObjectMapper().writeValueAsString(definition);
    assertThat(json).doesNotContain("UI-only", "renamed_", "主表", "明细");
    assertThat(FormCalculationPublicationProjectionTest.projection().compile(version, "model-a", false)).isEqualTo(definition);
    var requiredField = FormSubmissionRequirements.compileCalculated(document).stream().filter(r -> r.field().fieldId().equals("total")).findFirst().orElseThrow();
    assertThat(requiredField.forState(FormRecordState.DRAFT).missing(Map.of())).isFalse();
    assertThat(requiredField.forState(FormRecordState.SUBMITTED).missing(Map.of())).isTrue();
  }

  @Test
  void rejectsNonpersistentAggregateSourcesAndPreservesEmptyLegacyValidationAbsence() {
    var transientNumber = new FormFieldDefinition("transient", null, "Transient", null, FormFieldType.DECIMAL,
        FormStorageMode.TRANSIENT, false, false, null, 18, 2, null, List.of());
    var document = new ApplicationFormDocument("1", List.of(transientNumber), List.of(), List.of(), List.of(), List.of(),
        List.of(new FormValidationConstraint("invalid", FormValidationConstraint.Kind.RANGE, "transient", null, null, null, "0", null, "Invalid")));
    assertThatThrownBy(() -> FormCalculationValidationProjection.compile(document)).isInstanceOf(IllegalArgumentException.class);
    assertThat(FormCalculationValidationProjection.compile(new ApplicationFormDocument("1", List.of(), List.of(), List.of(), List.of()))).isEmpty();
  }

  private static FormFieldDefinition required(FormFieldDefinition field) {
    return new FormFieldDefinition(field.fieldId(), field.storageCode(), field.label(), field.storageLabel(), field.type(), field.storageMode(),
        true, field.storageRequired(), field.length(), field.precision(), field.scale(), field.defaultValue(), field.choices(), field.writePolicy(),
        field.validation(), field.access(), field.calculation());
  }

  @Test
  void keepsTheFullBusinessFieldCapacityWhenAddingTheLifecycleConstraint() {
    var fields = java.util.stream.IntStream.range(0, 300).mapToObj(index -> new FormFieldDefinition(
        "field" + index, "column_" + index, "Field", "Field", FormFieldType.STRING, FormStorageMode.STORED,
        index == 0, false, 100, null, null, null, List.of(), null,
        new org.simplepoint.plugin.app.form.api.model.FormFieldValidation(1, 100, null, null))).toList();
    var document = new ApplicationFormDocument("1", fields, List.of(), List.of(), List.of());
    var definition = FormCalculationPublicationProjectionTest.projection().compile(FormCalculationPublicationProjectionTest.version(document), "model-a", true);
    assertThat(definition.constraintsOrEmpty()).hasSize(301).containsKey("platform_record_state");
    assertThat(definition.validationsOrEmpty()).hasSize(1);
  }

  @Test
  void preservesDistinctHistoricalTextPoliciesAndDeduplicatesIdenticalRequirements() {
    var first = historicalField("first_column", "旧标题", 3, "^中", List.of());
    var second = historicalField("second_column", "新标题", 2, "😀$", List.of());
    var a = new ApplicationFormDocument("1", List.of(first), List.of(), List.of(), List.of());
    var b = new ApplicationFormDocument("1", List.of(second), List.of(), List.of(), List.of());
    var plan = FormPublicationRequirementsCompiler.compile("model", "app", "form", List.of(
        new FormPublicationRequirementsCompiler.Version(new org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference(1, "a".repeat(64)), a),
        new FormPublicationRequirementsCompiler.Version(new org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference(2, "b".repeat(64)), b)), b, true);
    assertThat(plan.validations()).hasSize(2).allMatch(rule -> rule.kind() == RecordValidationRule.Kind.FIELD);
    assertThat(plan.validations()).extracting(rule -> rule.constraints().pattern()).containsExactly("^中", "😀$");
    assertThat(FormCalculationValidationProjection.merge(plan.validations(), plan.validations())).isEqualTo(plan.validations());
    var renamed = new ApplicationFormDocument("1", List.of(historicalField("renamed", "另一个标题", 3, "^中", List.of())),
        List.of(), List.of(), List.of());
    assertThat(FormCalculationValidationProjection.pageRequirements(renamed)).isEqualTo(FormCalculationValidationProjection.pageRequirements(a));
  }

  @Test
  void retainsHistoricalChoiceLifecycleAndFrozenScalarValues() {
    var field = historicalField("column", "标题", 10, null,
        List.of(new org.simplepoint.plugin.app.form.api.model.FormChoiceOption("approved", "允许")));
    var document = new ApplicationFormDocument("1", List.of(field), List.of(), List.of(), List.of());
    var rules = FormCalculationValidationProjection.pageRequirements(document);
    assertThat(rules).hasSize(2);
    assertThat(rules.getFirst().when().operator()).isEqualTo(org.simplepoint.plugin.dna.runtime.api.record.RecordValueConditionOperator.TRUE);
    assertThat(rules.getLast().constraints().choices()).extracting(value -> value.canonicalValue()).containsExactly("approved");
    assertThat(rules.getLast().when().values()).extracting(value -> value.canonicalValue()).containsExactly("SUBMITTED");
    var projected = FormCalculationPublicationProjectionTest.projection().compile(FormCalculationPublicationProjectionTest.version(document),
        "model", true, rules);
    assertThat(projected.validationsOrEmpty()).containsAll(rules);
    assertThat(projected.constraintsOrEmpty()).containsKey("platform_record_state");
  }

  private static FormFieldDefinition historicalField(String column, String label, int max, String pattern,
      List<org.simplepoint.plugin.app.form.api.model.FormChoiceOption> choices) {
    return new FormFieldDefinition("note", column, label, label, FormFieldType.STRING, FormStorageMode.STORED, false, false,
        100, null, null, null, choices, null, new org.simplepoint.plugin.app.form.api.model.FormFieldValidation(1, max, pattern, null));
  }

  private static FormFieldDefinition plain(String id, FormFieldType type) {
    return new FormFieldDefinition(id, "renamed_" + id, id, id, type, FormStorageMode.STORED, false, false,
        type == FormFieldType.STRING ? 100 : null, null, null, null, List.of());
  }

  private static List<RecordValidationCondition> leaves(RecordValidationCondition condition) {
    return condition.children().isEmpty() ? List.of(condition) : condition.children().stream().flatMap(child -> leaves(child).stream()).toList();
  }
}
