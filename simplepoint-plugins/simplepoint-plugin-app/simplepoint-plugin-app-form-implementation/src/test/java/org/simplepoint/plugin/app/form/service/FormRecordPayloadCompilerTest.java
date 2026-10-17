package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.ApplicationFormApiVersion;
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
import org.simplepoint.plugin.app.form.api.model.FormRuleDefinition;
import org.simplepoint.plugin.app.form.api.model.FormRuleEffect;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;

class FormRecordPayloadCompilerTest {

  private final FormRecordPayloadCompiler compiler = new FormRecordPayloadCompiler();

  @Test
  void draftsMayBeIncompleteButSubmittedRecordsUseFieldIdentitiesAndLifecycleState() {
    var draft = compiler.compile(
        document(List.of()),
        Map.of(),
        Map.of(),
        null,
        FormRecordState.DRAFT
    );

    assertThat(draft).containsExactlyEntriesOf(Map.of("record_state", "DRAFT"));
    assertThatThrownBy(() -> compiler.compile(
        document(List.of()),
        Map.of(),
        Map.of(),
        null,
        FormRecordState.SUBMITTED
    )).isInstanceOfSatisfying(ApplicationFormException.class, exception ->
        assertThat(exception.errorCode()).isEqualTo(ApplicationFormErrorCode.RECORD_INVALID));

    var submitted = compiler.compile(
        document(List.of()),
        Map.of(),
        Map.of("name-field", "Ada"),
        FormRecordState.DRAFT,
        FormRecordState.SUBMITTED
    );
    assertThat(submitted).containsExactly(
        Map.entry("customer_name", "Ada"),
        Map.entry("record_state", "SUBMITTED")
    );
  }

  @Test
  void evaluatesTheSameConditionalRequiredRuleAsTheClientRuntime() {
    FormRuleDefinition rule = new FormRuleDefinition(
        "require-name",
        "name-control",
        FormRuleEffect.REQUIRE,
        new FormCondition(
            FormConditionOperator.EQUALS,
            "kind-field",
            List.of(new FormValue(FormValueKind.STRING, "person")),
            List.of()
        )
    );

    assertThat(compiler.compile(
        document(List.of(rule)),
        Map.of(),
        Map.of("kind-field", "company"),
        null,
        FormRecordState.SUBMITTED
    )).containsEntry("record_state", "SUBMITTED");

    assertThatThrownBy(() -> compiler.compile(
        document(List.of(rule)),
        Map.of(),
        Map.of("kind-field", "person"),
        null,
        FormRecordState.SUBMITTED
    )).isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void rejectsLifecycleRollbackAndComputedFieldInjection() {
    assertThatThrownBy(() -> compiler.compile(
        document(List.of()),
        Map.of("name-field", "Ada"),
        Map.of(),
        FormRecordState.SUBMITTED,
        FormRecordState.DRAFT
    )).isInstanceOf(ApplicationFormException.class);

    assertThatThrownBy(() -> compiler.compile(
        document(List.of()),
        Map.of(),
        Map.of("computed-field", "forged"),
        null,
        FormRecordState.DRAFT
    )).isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void validatesRequiredPersistentFieldsEvenWhenTheyAreNotRendered() {
    FormFieldDefinition required = field(
        "required-field",
        "required_value",
        FormStorageMode.STORED,
        true
    );
    FormComponentDefinition root = component("root", null, "layout.form", List.of());
    ApplicationFormDocument document = new ApplicationFormDocument(
        ApplicationFormApiVersion.DOCUMENT_V1,
        List.of(required),
        List.of(),
        List.of(new FormSceneDefinition(
            "main", "main", "Main", FormSceneType.MAIN, null, root
        )),
        List.of()
    );

    assertThatThrownBy(() -> compiler.compile(
        document,
        Map.of(),
        Map.of(),
        null,
        FormRecordState.SUBMITTED
    )).isInstanceOfSatisfying(ApplicationFormException.class, exception ->
        assertThat(exception).hasMessage(
            "Required form record fields are missing: required-field"
        ));
  }

  @Test
  void reportsRepeatedRequiredFieldOnlyOnce() {
    FormFieldDefinition required = field(
        "required-field",
        "required_value",
        FormStorageMode.STORED,
        true
    );
    FormComponentDefinition root = component("root", null, "layout.form", List.of(
        component("required-control-a", required.fieldId(), "field.text", List.of()),
        component("required-control-b", required.fieldId(), "field.text", List.of())
    ));
    ApplicationFormDocument document = new ApplicationFormDocument(
        ApplicationFormApiVersion.DOCUMENT_V1,
        List.of(required),
        List.of(),
        List.of(new FormSceneDefinition(
            "main", "main", "Main", FormSceneType.MAIN, null, root
        )),
        List.of()
    );

    assertThatThrownBy(() -> compiler.compile(
        document,
        Map.of(),
        Map.of(),
        null,
        FormRecordState.SUBMITTED
    )).isInstanceOfSatisfying(ApplicationFormException.class, exception ->
        assertThat(exception).hasMessage(
            "Required form record fields are missing: required-field"
        ));
  }

  @Test
  void validatesDialogRulesAndInheritsContainerVisibility() {
    FormRuleDefinition required = new FormRuleDefinition(
        "required-overlay", "overlay-name", FormRuleEffect.REQUIRE,
        new FormCondition(FormConditionOperator.IS_NOT_EMPTY, "kind-field", List.of(), List.of())
    );
    FormRuleDefinition hidden = new FormRuleDefinition(
        "hidden-overlay", "overlay-root", FormRuleEffect.HIDE,
        new FormCondition(FormConditionOperator.EQUALS, "kind-field",
            List.of(new FormValue(FormValueKind.STRING, "hidden")), List.of())
    );
    var base = document(List.of(required, hidden));
    var overlay = new FormSceneDefinition(
        "overlay", "overlay", "Overlay", FormSceneType.DIALOG, 640,
        component("overlay-root", null, "layout.group", List.of(
            component("overlay-name", "name-field", "field.text", List.of())
        ))
    );
    var source = new ApplicationFormDocument(base.contractVersion(), base.fields(), base.indexes(),
        List.of(base.scenes().getFirst(), overlay), base.rules());

    assertThatThrownBy(() -> compiler.compile(source, Map.of(), Map.of("kind-field", "visible"),
        null, FormRecordState.SUBMITTED)).isInstanceOf(ApplicationFormException.class)
        .hasMessageContaining("name-field");
    assertThat(compiler.compile(source, Map.of(), Map.of("kind-field", "hidden"),
        null, FormRecordState.SUBMITTED)).containsEntry("record_state", "SUBMITTED");
  }

  @Test
  void enforcesChoicesForDraftWritesAndCompleteSubmissionWithoutLeakingValues() {
    var field = new FormFieldDefinition("kind-field", "kind", "Kind", "Kind",
        FormFieldType.STRING, FormStorageMode.STORED, false, false, 120, null, null, null,
        List.of(new FormChoiceOption("person", "Person"), new FormChoiceOption("company", "Company")));
    var source = new ApplicationFormDocument("1", List.of(field), List.of(), List.of(), List.of());
    for (var state : FormRecordState.values()) {
      assertThatThrownBy(() -> compiler.compile(source, Map.of(),
          Map.of("kind-field", "forged-sensitive-value"), null, state))
          .isInstanceOf(ApplicationFormException.class)
          .hasMessage("Form record choice is invalid: kind-field");
    }
    assertThat(compiler.compile(source, Map.of(), Map.of("kind-field", "person"),
        null, FormRecordState.DRAFT)).containsEntry("kind", "person");
    var cleared = new java.util.HashMap<String, Object>();
    cleared.put("kind-field", null);
    assertThat(compiler.compile(source, Map.of("kind-field", "retired"), cleared,
        FormRecordState.DRAFT, FormRecordState.DRAFT)).containsEntry("kind", null);
    assertThat(compiler.compile(source, Map.of("kind-field", "retired"), Map.of(),
        FormRecordState.DRAFT, FormRecordState.DRAFT)).doesNotContainKey("kind");
    assertThatThrownBy(() -> compiler.compile(source, Map.of("kind-field", "retired"), Map.of(),
        FormRecordState.DRAFT, FormRecordState.SUBMITTED))
        .isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void acceptsEquivalentExactDecimalChoicesAndRejectsAdjacentValues() {
    var field = new FormFieldDefinition("amount", "amount", "Amount", "Amount",
        FormFieldType.DECIMAL, FormStorageMode.STORED, false, false, null, 30, 8, null,
        List.of(new FormChoiceOption("9999999999999999999.12345678", "Exact")));
    var source = new ApplicationFormDocument("1", List.of(field), List.of(), List.of(), List.of());
    assertThat(compiler.compile(source, Map.of(),
        Map.of("amount", "9999999999999999999.123456780"), null, FormRecordState.SUBMITTED))
        .containsEntry("record_state", "SUBMITTED");
    assertThatThrownBy(() -> compiler.compile(source, Map.of(),
        Map.of("amount", "9999999999999999999.12345679"), null, FormRecordState.DRAFT))
        .isInstanceOf(ApplicationFormException.class);
  }

  private static ApplicationFormDocument document(final List<FormRuleDefinition> rules) {
    FormFieldDefinition name = field(
        "name-field",
        "customer_name",
        FormStorageMode.STORED,
        rules.isEmpty()
    );
    FormFieldDefinition kind = field(
        "kind-field",
        "customer_kind",
        FormStorageMode.STORED,
        false
    );
    FormFieldDefinition computed = field(
        "computed-field",
        null,
        FormStorageMode.COMPUTED,
        false
    );
    FormComponentDefinition root = component("root", null, "layout.form", List.of(
        component("kind-control", kind.fieldId(), "field.text", List.of()),
        component("name-control", name.fieldId(), "field.text", List.of())
    ));
    return new ApplicationFormDocument(
        ApplicationFormApiVersion.DOCUMENT_V1,
        List.of(name, kind, computed),
        List.of(),
        List.of(new FormSceneDefinition(
            "main", "main", "Main", FormSceneType.MAIN, null, root
        )),
        rules
    );
  }

  private static FormFieldDefinition field(
      final String id,
      final String code,
      final FormStorageMode mode,
      final boolean required
  ) {
    return new FormFieldDefinition(
        id,
        code,
        id,
        code,
        FormFieldType.STRING,
        mode,
        required,
        false,
        120,
        null,
        null,
        null,
        List.of()
    );
  }

  private static FormComponentDefinition component(
      final String id,
      final String fieldId,
      final String type,
      final List<FormComponentDefinition> children
  ) {
    return new FormComponentDefinition(
        id,
        type,
        ApplicationFormApiVersion.RENDERER_V1,
        fieldId,
        24,
        null,
        null,
        null,
        null,
        false,
        Map.of(),
        children
    );
  }
}
