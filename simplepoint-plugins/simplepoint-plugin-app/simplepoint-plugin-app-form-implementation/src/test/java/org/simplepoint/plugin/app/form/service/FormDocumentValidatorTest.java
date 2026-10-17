package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.ApplicationFormApiVersion;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormCondition;
import org.simplepoint.plugin.app.form.api.model.FormConditionOperator;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormFieldWritePolicy;
import org.simplepoint.plugin.app.form.api.model.FormIndexDefinition;
import org.simplepoint.plugin.app.form.api.model.FormRuleDefinition;
import org.simplepoint.plugin.app.form.api.model.FormRuleEffect;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;

/** Exercises bounded document rules before persistence or publication. */
class FormDocumentValidatorTest {

  private final FormDocumentValidator validator = new FormDocumentValidator(
      new DefaultFormComponentRegistry()
  );

  @Test
  void rejectsRequiredEffectsOnContainersAndUnboundedNumericRuleLiterals() {
    var fields = List.of(field("name", "name"));
    var scenes = List.of(main(container("root", List.of(fieldComponent("input", "name")))));
    var predicate = new FormCondition(FormConditionOperator.IS_NOT_EMPTY, "name", List.of(), List.of());
    for (var effect : List.of(FormRuleEffect.REQUIRE, FormRuleEffect.OPTIONAL)) {
      assertInvalid(document(fields, scenes, List.of(new FormRuleDefinition("required-root", "root", effect, predicate))));
      validator.validate(document(fields, scenes, List.of(new FormRuleDefinition("required-input", "input", effect, predicate))));
    }
    var amount = new FormFieldDefinition("amount", "amount", "Amount", "Amount", FormFieldType.DECIMAL,
        FormStorageMode.STORED, false, false, null, 18, 2, null, List.of());
    var excessive = new FormCondition(FormConditionOperator.GREATER_THAN, "amount",
        List.of(new FormValue(FormValueKind.DECIMAL, "1e2147483647")), List.of());
    assertInvalid(document(List.of(amount), List.of(main(container("root", List.of()))),
        List.of(new FormRuleDefinition("unsafe-number", "root", FormRuleEffect.HIDE, excessive))));
  }

  @Test
  void validatesDefaultLengthPrecisionAndScaleWithoutRequiringChoices() {
    var scenes = List.of(main(container("root", List.of())));
    var text = new FormFieldDefinition("name", "name", "Name", "Name", FormFieldType.STRING,
        FormStorageMode.STORED, false, false, 2, null, null,
        new FormValue(FormValueKind.STRING, "三个字"), List.of());
    assertInvalid(document(List.of(text), scenes));
    for (String literal : List.of("1000", "1.001", "999.991", "1e2147483647", "1e-2147483647")) {
      var decimal = new FormFieldDefinition("amount", "amount", "Amount", "Amount", FormFieldType.DECIMAL,
          FormStorageMode.STORED, false, false, null, 5, 2,
          new FormValue(FormValueKind.DECIMAL, literal), List.of());
      assertInvalid(document(List.of(decimal), scenes));
    }
    for (String literal : List.of("999.9900", "0", "-999.99")) {
      var decimal = new FormFieldDefinition("amount", "amount", "Amount", "Amount", FormFieldType.DECIMAL,
          FormStorageMode.STORED, false, true, null, 5, 2,
          new FormValue(FormValueKind.DECIMAL, literal), List.of());
      validator.validate(document(List.of(decimal), scenes));
    }
    var exact = new FormFieldDefinition("amount", "amount", "Amount", "Amount", FormFieldType.DECIMAL,
        FormStorageMode.STORED, false, false, null, 38, 18,
        new FormValue(FormValueKind.DECIMAL, "12345678901234567890.123456789012345678"), List.of());
    validator.validate(document(List.of(exact), scenes));
  }

  @Test
  void rejectsInvalidTemporalAndJsonDefaultsWithDomainErrors() {
    var cases = Map.of(
        FormFieldType.DATE, new FormValue(FormValueKind.DATE, "2026-02-29"),
        FormFieldType.TIME, new FormValue(FormValueKind.TIME, "25:00:00"),
        FormFieldType.DATETIME, new FormValue(FormValueKind.DATETIME, "not-an-instant")
    );
    cases.forEach((type, value) -> {
      var field = new FormFieldDefinition("value", "value", "Value", "Value", type,
          FormStorageMode.STORED, false, false, null, null, null, value, List.of());
      assertInvalid(document(List.of(field), List.of(main(container("root", List.of())))));
    });
    for (String literal : List.of(" ", "{} {}", "{bad}")) {
      var field = new FormFieldDefinition("value", "value", "Value", "Value", FormFieldType.JSON,
          FormStorageMode.STORED, false, false, null, null, null,
          new FormValue(FormValueKind.JSON, literal), List.of());
      assertInvalid(document(List.of(field), List.of(main(container("root", List.of())))));
    }
  }

  @Test
  void rejectsBusinessWritePoliciesWithoutPersistentRecordValues() {
    for (var mode : List.of(FormStorageMode.TRANSIENT, FormStorageMode.COMPUTED)) {
      var field = new FormFieldDefinition("display_name", null, "Display name", null,
          FormFieldType.STRING, mode, false, false, 120, null, null, null, List.of(),
          FormFieldWritePolicy.CREATE_ONLY);
      assertInvalid(document(List.of(field),
          List.of(main(container("root", List.of(fieldComponent("name", field.fieldId())))))));
    }
  }

  @Test
  void acceptsMainSceneWithCompatibleStableFieldBinding() {
    validator.validate(document(
        List.of(field("customer_name", "customer_name")),
        List.of(main(container(
            "root",
            List.of(fieldComponent("name_input", "customer_name"))
        )))
    ));
  }

  @Test
  void rejectsMultipleMainScenesAndUnknownComponentTypes() {
    assertInvalid(document(
        List.of(),
        List.of(main(container("root_a", List.of())), main(container("root_b", List.of())))
    ));

    assertInvalid(document(
        List.of(),
        List.of(main(new FormComponentDefinition(
            "root",
            "untrusted.script",
            ApplicationFormApiVersion.RENDERER_V1,
            null,
            24,
            null,
            null,
            null,
            null,
            false,
            Map.of(),
            List.of()
        )))
    ));
  }

  @Test
  void rejectsDuplicateStorageCodesAndMissingSceneTargets() {
    assertInvalid(document(
        List.of(field("name_a", "name"), field("name_b", "name")),
        List.of(main(container("root", List.of())))
    ));

    FormComponentDefinition openMissing = new FormComponentDefinition(
        "open_missing",
        "action.open-dialog",
        ApplicationFormApiVersion.RENDERER_V1,
        null,
        24,
        "Open",
        null,
        null,
        "missing",
        false,
        Map.of(),
        List.of()
    );
    assertInvalid(document(List.of(), List.of(main(container("root", List.of(openMissing))))));
  }

  @Test
  void rejectsFieldIdentitiesReservedByTheRuntimeRecordEnvelope() {
    assertInvalid(document(
        List.of(field("id", "business_id")),
        List.of(main(container("root", List.of())))
    ));
    assertInvalid(document(
        List.of(field("recordState", "business_state")),
        List.of(main(container("root", List.of())))
    ));
  }

  @Test
  void rejectsIncompatibleFieldComponentBinding() {
    FormFieldDefinition date = new FormFieldDefinition(
        "birth_date",
        "birth_date",
        "Birth date",
        "Birth date",
        FormFieldType.DATE,
        FormStorageMode.STORED,
        false,
        false,
        null,
        null,
        null,
        null,
        List.of()
    );
    assertInvalid(document(List.of(date), List.of(main(fieldComponent("input", "birth_date")))));
  }

  @Test
  void requiresSceneOpeningActionsToMatchTheirTargetContainerType() {
    FormComponentDefinition openAsDialog = action(
        "open_details",
        "action.open-dialog",
        "details"
    );
    FormSceneDefinition drawer = new FormSceneDefinition(
        "details",
        "details",
        "Details",
        FormSceneType.DRAWER,
        480,
        container("drawer_root", List.of())
    );

    assertInvalid(document(
        List.of(),
        List.of(main(container("root", List.of(openAsDialog))), drawer)
    ));
  }

  @Test
  void enforcesPredicateCardinalityAndOrderedFieldTypes() {
    FormFieldDefinition name = field("customer_name", "customer_name");
    FormComponentDefinition input = fieldComponent("name_input", name.fieldId());

    assertInvalid(document(
        List.of(name),
        List.of(main(container("root", List.of(input)))),
        List.of(rule(input.componentId(), new FormCondition(
            FormConditionOperator.EQUALS,
            name.fieldId(),
            List.of(stringValue("A"), stringValue("B")),
            List.of()
        )))
    ));
    assertInvalid(document(
        List.of(name),
        List.of(main(container("root", List.of(input)))),
        List.of(rule(input.componentId(), new FormCondition(
            FormConditionOperator.GREATER_THAN,
            name.fieldId(),
            List.of(stringValue("A")),
            List.of()
        )))
    ));
  }

  @Test
  void rejectsStorageRequiredFieldsWithoutNonNullDefaults() {
    FormFieldDefinition field = new FormFieldDefinition(
        "customer_name",
        "customer_name",
        "Name",
        "Customer name",
        FormFieldType.STRING,
        FormStorageMode.STORED,
        true,
        true,
        120,
        null,
        null,
        null,
        List.of()
    );

    assertInvalid(document(
        List.of(field),
        List.of(main(container("root", List.of(fieldComponent("name", field.fieldId())))))
    ));
  }

  @Test
  void rejectsIndexesOverTransientFields() {
    FormFieldDefinition field = new FormFieldDefinition(
        "display_name",
        null,
        "Display name",
        null,
        FormFieldType.STRING,
        FormStorageMode.TRANSIENT,
        false,
        false,
        120,
        null,
        null,
        null,
        List.of()
    );
    ApplicationFormDocument document = new ApplicationFormDocument(
        ApplicationFormApiVersion.DOCUMENT_V1,
        List.of(field),
        List.of(new FormIndexDefinition(
            "display_name_index",
            "idx_display_name",
            false,
            List.of(field.fieldId())
        )),
        List.of(main(container("root", List.of(fieldComponent("name", field.fieldId()))))),
        List.of()
    );

    assertInvalid(document);
  }

  private void assertInvalid(final ApplicationFormDocument document) {
    assertThatThrownBy(() -> validator.validate(document))
        .isInstanceOfSatisfying(ApplicationFormException.class, exception ->
            org.assertj.core.api.Assertions.assertThat(exception.errorCode())
                .isEqualTo(ApplicationFormErrorCode.DOCUMENT_INVALID));
  }

  private static ApplicationFormDocument document(
      final List<FormFieldDefinition> fields,
      final List<FormSceneDefinition> scenes
  ) {
    return document(fields, scenes, List.of());
  }

  private static ApplicationFormDocument document(
      final List<FormFieldDefinition> fields,
      final List<FormSceneDefinition> scenes,
      final List<FormRuleDefinition> rules
  ) {
    return new ApplicationFormDocument(
        ApplicationFormApiVersion.DOCUMENT_V1,
        fields,
        List.of(),
        scenes,
        rules
    );
  }

  private static FormRuleDefinition rule(
      final String componentId,
      final FormCondition condition
  ) {
    return new FormRuleDefinition("rule", componentId, FormRuleEffect.SHOW, condition);
  }

  private static FormValue stringValue(final String value) {
    return new FormValue(FormValueKind.STRING, value);
  }

  private static FormFieldDefinition field(final String fieldId, final String storageCode) {
    return new FormFieldDefinition(
        fieldId,
        storageCode,
        "Name",
        "Name",
        FormFieldType.STRING,
        FormStorageMode.STORED,
        false,
        false,
        120,
        null,
        null,
        null,
        List.of()
    );
  }

  private static FormSceneDefinition main(final FormComponentDefinition root) {
    return new FormSceneDefinition("main", "main", "Main", FormSceneType.MAIN, null, root);
  }

  private static FormComponentDefinition container(
      final String id,
      final List<FormComponentDefinition> children
  ) {
    return new FormComponentDefinition(
        id,
        "layout.form",
        ApplicationFormApiVersion.RENDERER_V1,
        null,
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

  private static FormComponentDefinition fieldComponent(
      final String id,
      final String fieldId
  ) {
    return new FormComponentDefinition(
        id,
        "field.text",
        ApplicationFormApiVersion.RENDERER_V1,
        fieldId,
        24,
        null,
        null,
        null,
        null,
        false,
        Map.of(),
        List.of()
    );
  }

  private static FormComponentDefinition action(
      final String id,
      final String type,
      final String targetSceneId
  ) {
    return new FormComponentDefinition(
        id,
        type,
        ApplicationFormApiVersion.RENDERER_V1,
        null,
        24,
        null,
        null,
        null,
        targetSceneId,
        false,
        Map.of(),
        List.of()
    );
  }
}
