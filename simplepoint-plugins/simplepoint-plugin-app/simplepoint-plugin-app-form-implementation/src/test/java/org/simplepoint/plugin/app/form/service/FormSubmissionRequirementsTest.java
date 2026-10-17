package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormCondition;
import org.simplepoint.plugin.app.form.api.model.FormConditionOperator;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormFieldWritePolicy;
import org.simplepoint.plugin.app.form.api.model.FormRuleDefinition;
import org.simplepoint.plugin.app.form.api.model.FormRuleEffect;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;

/** Submission and publication must share requirements for all active historical scenes. */
class FormSubmissionRequirementsTest {

  @Test
  void combinesScenesAndAncestorVisibilityWithoutTreatingDisabledFieldsAsOptional() {
    var source = document(false, List.of(
        rule("main-required", "main-input", FormRuleEffect.REQUIRE, "main"),
        rule("dialog-required", "dialog-input", FormRuleEffect.REQUIRE, "dialog"),
        rule("dialog-visible", "dialog-root", FormRuleEffect.SHOW, "dialog"),
        rule("drawer-optional", "drawer-input", FormRuleEffect.OPTIONAL, "optional"),
        rule("drawer-visible", "drawer-group", FormRuleEffect.SHOW, "drawer"),
        rule("drawer-disabled", "drawer-input", FormRuleEffect.DISABLE, "drawer")
    ));
    var requirement = FormSubmissionRequirements.compile(source).getFirst();
    for (String value : List.of("main", "dialog", "drawer")) {
      assertThat(requirement.missing(Map.of("kind", value))).as(value).isTrue();
    }
    for (String value : List.of("optional", "other")) {
      assertThat(requirement.missing(Map.of("kind", value))).as(value).isFalse();
    }
    assertThat(requirement.missing(Map.of("kind", "dialog", "name", "Ada"))).isFalse();
  }

  @Test
  void lastRuleWinsIndependentlyForVisibilityAndRequirement() {
    var source = document(false, List.of(
        rule("initial-required", "main-input", FormRuleEffect.REQUIRE, "old"),
        rule("replacement-required", "main-input", FormRuleEffect.REQUIRE, "new"),
        rule("initial-visible", "main-input", FormRuleEffect.SHOW, "old"),
        rule("replacement-hidden", "main-input", FormRuleEffect.HIDE, "hidden")
    ));
    var requirement = FormSubmissionRequirements.compile(source).getFirst();
    assertThat(requirement.missing(Map.of("kind", "new"))).isTrue();
    assertThat(requirement.missing(Map.of("kind", "old"))).isFalse();
    assertThat(requirement.missing(Map.of("kind", "hidden"))).isFalse();
  }

  @Test
  void preservesTwoValuedNullNegationAndExactLargeDecimalConditions() {
    var absent = new FormCondition(FormConditionOperator.NOT_EQUALS, "kind",
        List.of(new FormValue(FormValueKind.STRING, "optional")), List.of());
    var large = new FormCondition(FormConditionOperator.GREATER_THAN, "amount",
        List.of(new FormValue(FormValueKind.DECIMAL, "99999999999999999.12345678")), List.of());
    var condition = new FormCondition(FormConditionOperator.AND, null, List.of(), List.of(absent, large));
    var source = document(false, List.of(new FormRuleDefinition("precise-required", "main-input",
        FormRuleEffect.REQUIRE, condition)));
    var requirement = FormSubmissionRequirements.compile(source).getFirst();
    assertThat(requirement.missing(Map.of("amount", "99999999999999999.12345679"))).isTrue();
    assertThat(requirement.missing(Map.of("amount", "99999999999999999.12345678"))).isFalse();
    assertThat(requirement.missing(Map.of("amount", "99999999999999999.12345679", "kind", "optional"))).isFalse();
  }

  @Test
  void preservesUnrenderedPersistentRequirementsAndDoesNotInventTransientRequirements() {
    var fields = List.of(field("name", true, FormStorageMode.STORED),
        field("reference", true, FormStorageMode.REFERENCE), field("attachment", true, FormStorageMode.ATTACHMENT),
        field("preview", true, FormStorageMode.TRANSIENT), field("derived", true, FormStorageMode.COMPUTED));
    var source = new ApplicationFormDocument("1", fields, List.of(), List.of(), List.of());
    var requirements = FormSubmissionRequirements.compile(source);
    assertThat(requirements).extracting(requirement -> requirement.field().fieldId())
        .containsExactly("name", "reference", "attachment");
    assertThat(requirements).allMatch(requirement -> requirement.missing(Map.of()));
  }

  @Test
  void keepsHistoricalSubmissionRulesWhenTheFormWideWritePolicyChanges() {
    var page = document(false, List.of(rule("historical-required", "main-input", FormRuleEffect.REQUIRE, "historic")));
    var original = page.fields().getFirst();
    var stricter = new FormFieldDefinition(original.fieldId(), original.storageCode(), original.label(), original.storageLabel(),
        original.type(), original.storageMode(), true, false, original.length(), null, null, null, List.of(),
        FormFieldWritePolicy.CREATE_ONLY);
    var governing = new ApplicationFormDocument("1", List.of(stricter), List.of(), List.of(), List.of());
    var projected = FormWritePolicyProjection.apply(page, governing);
    var requirement = FormSubmissionRequirements.compile(projected).getFirst();
    assertThat(requirement.field().writePolicy()).isEqualTo(FormFieldWritePolicy.CREATE_ONLY);
    assertThat(requirement.missing(Map.of("kind", "historic"))).isTrue();
    assertThat(requirement.missing(Map.of("kind", "other"))).isFalse();
    assertThat(page.fields().getFirst().effectiveWritePolicy()).isEqualTo(FormFieldWritePolicy.EDITABLE);
  }

  @Test
  void foldsImpossibleOccurrencesWithoutExpandingConditionTrees() {
    var source = document(false, List.of(
        rule("required", "main-input", FormRuleEffect.REQUIRE, "same"),
        rule("hidden", "main-input", FormRuleEffect.HIDE, "same")
    ));
    assertThat(FormSubmissionRequirements.compile(source).getFirst().predicate())
        .isEqualTo(FormSubmissionRequirements.Constant.FALSE);
    var condition = new FormCondition(FormConditionOperator.NOT, null, List.of(),
        List.of(new FormCondition(FormConditionOperator.IS_EMPTY, "kind", List.of(), List.of())));
    var requirement = FormSubmissionRequirements.compile(document(false, List.of(
        new FormRuleDefinition("required", "main-input", FormRuleEffect.REQUIRE, condition)
    ))).getFirst();
    assertThat(requirement.predicate()).isInstanceOfSatisfying(FormSubmissionRequirements.Condition.class,
        value -> assertThat(value.condition()).isEqualTo(condition));
  }

  private static ApplicationFormDocument document(final boolean required, final List<FormRuleDefinition> rules) {
    return new ApplicationFormDocument("1", List.of(field("name", required, FormStorageMode.STORED)), List.of(), List.of(
        scene("main", FormSceneType.MAIN, List.of(node("main-input", "name", List.of()))),
        scene("dialog", FormSceneType.DIALOG, List.of(node("dialog-input", "name", List.of()))),
        scene("drawer", FormSceneType.DRAWER,
            List.of(node("drawer-group", null, List.of(node("drawer-input", "name", List.of())))))
    ), rules);
  }

  private static FormFieldDefinition field(final String id, final boolean required, final FormStorageMode mode) {
    return new FormFieldDefinition(id, id, id, id, FormFieldType.STRING, mode, required,
        false, 120, null, null, null, List.of());
  }

  private static FormSceneDefinition scene(final String id, final FormSceneType type, final List<FormComponentDefinition> children) {
    return new FormSceneDefinition(id, id, id, type, null, node(id + "-root", null, children));
  }

  private static FormComponentDefinition node(final String id, final String field, final List<FormComponentDefinition> children) {
    return new FormComponentDefinition(id, field == null ? "layout.form" : "field.text", "1", field, 24,
        null, null, null, null, false, Map.of(), children);
  }

  private static FormRuleDefinition rule(final String id, final String target, final FormRuleEffect effect, final String value) {
    return new FormRuleDefinition(id, target, effect, new FormCondition(FormConditionOperator.EQUALS, "kind",
        List.of(new FormValue(FormValueKind.STRING, value)), List.of()));
  }
}
