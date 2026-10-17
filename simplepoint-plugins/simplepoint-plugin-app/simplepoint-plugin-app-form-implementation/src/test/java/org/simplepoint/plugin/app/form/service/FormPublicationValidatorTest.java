package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.validation.InvalidFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormCondition;
import org.simplepoint.plugin.app.form.api.model.FormConditionOperator;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormRuleDefinition;
import org.simplepoint.plugin.app.form.api.model.FormRuleEffect;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;

/** Proves incomplete drafts cannot become versions with impossible mandatory inputs. */
class FormPublicationValidatorTest {
  private final FormDocumentValidator validator = new FormDocumentValidator(
      new DefaultFormComponentRegistry()
  );

  @Test
  void savesUnboundRequiredFieldsButRejectsTheirPublication() {
    var document = document(null, List.of(), List.of());
    validator.validate(document);
    assertNotReady(document);
  }

  @Test
  void distinguishesReachableAuxiliaryInputsFromOrphanScenes() {
    var dialog = new FormSceneDefinition("dialog", "dialog", "Dialog", FormSceneType.DIALOG,
        640, node("dialog-root", "layout.form", null, null, false,
            List.of(node("input", "field.text", "name", null, false, List.of()))));
    assertNotReady(document(null, List.of(), List.of(dialog)));
    validator.validatePublication(document(null,
        List.of(node("open", "action.open-dialog", null, "dialog", false, List.of())),
        List.of(dialog)));
    assertNotReady(document(null,
        List.of(node("open", "action.open-dialog", null, "dialog", true, List.of())), List.of(dialog)));
    assertNotReady(document(null, List.of(node("disabled-group", "layout.group", null, null, true,
        List.of(node("open", "action.open-dialog", null, "dialog", false, List.of())))), List.of(dialog)));
  }

  @Test
  void readOnlyContainersCannotSupplyMandatoryInputs() {
    assertNotReady(document(null, List.of(node("group", "layout.group", null, null, true,
        List.of(node("input", "field.text", "name", null, false, List.of())))), List.of()));
  }

  @Test
  void acceptsUsableDefaultsAndRejectsBlankDefaults() {
    validator.validatePublication(document(new FormValue(FormValueKind.STRING, "Unnamed"),
        List.of(), List.of()));
    assertNotReady(document(new FormValue(FormValueKind.STRING, "  "), List.of(), List.of()));
  }

  @Test
  void publicationUsesSubmissionWhitespaceSemanticsForDefaults() {
    for (String empty : List.of("\u00A0", "\u3000", "\uFEFF", "\t\u00A0\uFEFF\n")) {
      assertNotReady(document(new FormValue(FormValueKind.STRING, empty), List.of(), List.of()));
    }
    for (String nonEmpty : List.of("\u0085", "\u200B", "名前")) {
      validator.validatePublication(document(new FormValue(FormValueKind.STRING, nonEmpty), List.of(), List.of()));
    }
  }

  @Test
  void conditionalRequiredInputsMustAlsoBeReachableAndEditable() {
    var dialog = new FormSceneDefinition("dialog", "dialog", "Dialog", FormSceneType.DIALOG,
        640, node("dialog-root", "layout.form", null, null, false,
            List.of(node("input", "field.text", "name", null, false, List.of()))));
    var optional = new FormFieldDefinition("name", "name", "Name", "Name", FormFieldType.STRING,
        FormStorageMode.STORED, false, false, 120, null, null, null, List.of());
    var condition = new FormCondition(FormConditionOperator.IS_EMPTY, "name", List.of(), List.of());
    var rule = new FormRuleDefinition("required", "input", FormRuleEffect.REQUIRE, condition);
    var base = document(null, List.of(), List.of(dialog));
    var orphan = new ApplicationFormDocument("1", List.of(optional), List.of(), base.scenes(), List.of(rule));
    validator.validate(orphan);
    assertNotReady(orphan);

    var reachable = document(null,
        List.of(node("open", "action.open-dialog", null, "dialog", false, List.of())), List.of(dialog));
    validator.validatePublication(new ApplicationFormDocument("1", List.of(optional), List.of(), reachable.scenes(), List.of(rule)));
    var readOnly = new ApplicationFormDocument("1", List.of(optional), List.of(), List.of(
        new FormSceneDefinition("main", "main", "Main", FormSceneType.MAIN, null,
            node("root", "layout.form", null, null, true,
                List.of(node("input", "field.text", "name", null, false, List.of()))))
    ), List.of(rule));
    assertNotReady(readOnly);
  }

  private void assertNotReady(final ApplicationFormDocument document) {
    assertThatThrownBy(() -> validator.validatePublication(document))
        .isInstanceOfSatisfying(InvalidFormException.class, failure ->
            org.assertj.core.api.Assertions.assertThat(failure.getViolations())
                .singleElement().extracting(violation -> violation.field())
                .isEqualTo("fields.name"));
  }

  private static ApplicationFormDocument document(
      final FormValue defaultValue,
      final List<FormComponentDefinition> children,
      final List<FormSceneDefinition> auxiliary
  ) {
    var field = new FormFieldDefinition("name", "name", "Name", "Name", FormFieldType.STRING,
        FormStorageMode.STORED, true, false, 120, null, null, defaultValue, List.of());
    var scenes = new java.util.ArrayList<FormSceneDefinition>();
    scenes.add(new FormSceneDefinition("main", "main", "Main", FormSceneType.MAIN, null,
        node("root", "layout.form", null, null, false, children)));
    scenes.addAll(auxiliary);
    return new ApplicationFormDocument("1", List.of(field), List.of(), scenes, List.of());
  }

  private static FormComponentDefinition node(
      final String id, final String type, final String field, final String target,
      final boolean readOnly, final List<FormComponentDefinition> children
  ) {
    return new FormComponentDefinition(id, type, "1", field, 24, null, null, null, target,
        readOnly, Map.of(), children);
  }
}
