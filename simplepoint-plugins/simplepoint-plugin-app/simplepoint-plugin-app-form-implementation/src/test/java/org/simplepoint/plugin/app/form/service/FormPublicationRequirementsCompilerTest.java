package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
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
import org.simplepoint.plugin.app.form.api.model.FormFieldWritePolicy;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormRuleDefinition;
import org.simplepoint.plugin.app.form.api.model.FormRuleEffect;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;
import org.simplepoint.plugin.app.form.service.FormPublicationRequirementsCompiler.Version;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCompletenessQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordJsonSemantics;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueConditionOperator;

class FormPublicationRequirementsCompilerTest {

  private static final RecordSchemaReference SCHEMA = new RecordSchemaReference(1, "a".repeat(64));
  private final FormRecordPayloadCompiler payloads = new FormRecordPayloadCompiler();

  @Test
  void optionalDraftsRemainEditableButConditionalChangesCannotStrandLockedFields() {
    var editable = field("name", false, FormFieldWritePolicy.EDITABLE);
    var kind = field("kind", false, FormFieldWritePolicy.EDITABLE);
    var condition = equals("kind", "person");
    var oldPage = document(List.of(editable, kind), List.of(required("name", condition)));
    var governing = document(List.of(field("name", false, FormFieldWritePolicy.CREATE_ONLY), kind), List.of());
    var effective = FormWritePolicyProjection.apply(oldPage, governing);
    payloads.compile(effective, Map.of(), Map.of("kind", "company"), null, FormRecordState.DRAFT);
    assertThatThrownBy(() -> payloads.compile(effective, Map.of("kind", "company"), Map.of("kind", "person"),
        FormRecordState.DRAFT, FormRecordState.DRAFT)).isInstanceOf(ApplicationFormException.class);
    payloads.compile(effective, Map.of("kind", "company", "name", "Ada"), Map.of("kind", "person"),
        FormRecordState.DRAFT, FormRecordState.DRAFT);
    assertThatThrownBy(() -> payloads.compile(effective, Map.of("kind", "company"), Map.of("kind", "person", "name", "Ada"),
        FormRecordState.DRAFT, FormRecordState.DRAFT)).isInstanceOf(ApplicationFormException.class);

    var checks = compile(List.of(new Version(SCHEMA, oldPage)), governing);
    assertThat(checks).hasSize(1);
    var check = checks.values().iterator().next();
    assertThat(check.fields()).containsExactly("column_name");
    assertThat(check.filter()).isNull(); // Both saved states must preserve the locked-field invariant.
    assertThat(check.condition().field()).isEqualTo("column_kind");
    assertThat(check.condition().values().getFirst().canonicalValue()).isEqualTo("person");
    assertThat(check.jsonSemantics()).isEqualTo(RecordJsonSemantics.STRUCTURAL_V1);
  }

  @Test
  void untilSubmittedFieldsCanBeFilledLaterAndBaseRequiredCreateOnlyFieldsCannotBecomeEmpty() {
    var later = document(List.of(field("name", true, FormFieldWritePolicy.UNTIL_SUBMITTED)), List.of());
    payloads.compile(later, Map.of(), Map.of(), null, FormRecordState.DRAFT);
    assertThatThrownBy(() -> payloads.compile(later, Map.of(), Map.of(), FormRecordState.DRAFT, FormRecordState.SUBMITTED))
        .isInstanceOf(ApplicationFormException.class);
    var query = compile(List.of(new Version(SCHEMA, later)), later).values().iterator().next();
    assertThat(query.filter().field()).isEqualTo("record_state");
    assertThat(query.filter().values()).containsExactly("SUBMITTED");

    var hidden = document(List.of(field("name", true, FormFieldWritePolicy.CREATE_ONLY),
        field("kind", false, FormFieldWritePolicy.EDITABLE)), List.of(new FormRuleDefinition("hide", "input-name",
        FormRuleEffect.HIDE, equals("kind", "hidden"))));
    assertThatThrownBy(() -> payloads.compile(hidden, Map.of("kind", "hidden"), Map.of(),
        FormRecordState.DRAFT, FormRecordState.DRAFT)).isInstanceOf(ApplicationFormException.class);
    assertThat(compile(List.of(new Version(SCHEMA, hidden)), hidden).values().iterator().next().condition()).isNull();
  }

  @Test
  void historicalPagesKeepTheirConditionsInsteadOfInheritingTheNewPageRequirements() {
    var fields = List.of(field("name", false, FormFieldWritePolicy.EDITABLE), field("kind", false, FormFieldWritePolicy.EDITABLE));
    var old = document(fields, List.of(required("name", equals("kind", "old"))));
    var candidate = document(fields, List.of(required("name", equals("kind", "new"))));
    var checks = compile(List.of(new Version(SCHEMA, old), new Version(SCHEMA, candidate)), candidate);
    assertThat(checks).hasSize(2);
    assertThat(checks.values()).extracting(check -> check.condition().values().getFirst().canonicalValue())
        .containsExactlyInAnyOrder("old", "new");
    assertThat(checks.values()).allSatisfy(check -> assertThat(check.filter().values()).containsExactly("SUBMITTED"));
  }

  @Test
  void foldsNonPersistentDefaultsButNeverTreatsStoredNullAsDefault() {
    var name = field("name", false, FormFieldWritePolicy.CREATE_ONLY);
    var transientKind = new FormFieldDefinition("kind", "column_kind", "Kind", "Kind", FormFieldType.STRING,
        FormStorageMode.TRANSIENT, false, false, 128, null, null, new FormValue(FormValueKind.STRING, "person"), List.of());
    var transientPage = document(List.of(name, transientKind), List.of(required("name", equals("kind", "person"))));
    var check = compile(List.of(new Version(SCHEMA, transientPage)), transientPage).values().iterator().next();
    assertThat(check.condition()).isNull();
    assertThat(check.filter()).isNull();
    var persistentKind = new FormFieldDefinition("kind", "column_kind", "Kind", "Kind", FormFieldType.STRING,
        FormStorageMode.STORED, false, false, 128, null, null, new FormValue(FormValueKind.STRING, "person"), List.of());
    var storedPage = document(List.of(name, persistentKind), List.of(required("name", equals("kind", "person"))));
    assertThat(compile(List.of(new Version(SCHEMA, storedPage)), storedPage).values().iterator().next().condition().field())
        .isEqualTo("column_kind");
  }

  @Test
  void preservesTypedNegationAndExactLiterals() {
    var amount = new FormFieldDefinition("amount", "column_amount", "Amount", "Amount", FormFieldType.DECIMAL,
        FormStorageMode.STORED, false, false, null, 38, 18, null, List.of());
    var predicate = new FormCondition(FormConditionOperator.NOT, null, List.of(), List.of(
        new FormCondition(FormConditionOperator.GREATER_THAN, "amount",
            List.of(new FormValue(FormValueKind.DECIMAL, "9999999999999999999.123456789012345678")), List.of())));
    var page = document(List.of(field("name", false, FormFieldWritePolicy.CREATE_ONLY), amount), List.of(required("name", predicate)));
    var check = compile(List.of(new Version(SCHEMA, page)), page).values().iterator().next();
    assertThat(check.condition().operator()).isEqualTo(RecordValueConditionOperator.NOT);
    var threshold = check.condition().children().getFirst();
    assertThat(threshold.operator()).isEqualTo(RecordValueConditionOperator.GREATER_THAN);
    assertThat(threshold.field()).isEqualTo("column_amount");
    assertThat(threshold.values().getFirst().canonicalValue()).isEqualTo("9999999999999999999.123456789012345678");
  }

  @Test
  void deduplicatesEquivalentPagesAndSplitsWideChecksWithoutOmittingFields() {
    var fields = IntStream.range(0, 300).mapToObj(index -> field("f" + index, true, FormFieldWritePolicy.EDITABLE)).toList();
    var page = document(fields, List.of());
    var checks = compile(List.of(new Version(SCHEMA, page), new Version(SCHEMA, page)), page);
    assertThat(checks).hasSize(3);
    assertThat(checks.values()).allSatisfy(check -> assertThat(check.fields()).hasSize(100));
    assertThat(checks.values().stream().flatMap(check -> check.fields().stream()).distinct().count()).isEqualTo(300);
  }

  @Test
  void checksRetiredChoiceValuesInSubmittedRecordsAndLockedDraftsWithoutRejectingSqlNull() {
    var field = new FormFieldDefinition("name", "column_name", "Name", "Name", FormFieldType.STRING, FormStorageMode.STORED,
        false, false, 128, null, null, null, List.of(new FormChoiceOption("active", "Active")), FormFieldWritePolicy.CREATE_ONLY);
    var page = document(List.of(field), List.of());
    var plan = FormPublicationRequirementsCompiler.compile("model", "app", "form", List.of(new Version(SCHEMA, page)), page);
    assertThat(plan.checks()).isEmpty(); // A nonempty retired choice is not a missing-value violation.
    assertThat(plan.valueChecks()).hasSize(1);
    var check = plan.valueChecks().values().iterator().next();
    assertThat(check.filter().operator()).isEqualTo(RecordFilterOperator.IS_NOT_NULL);
    assertThat(check.condition().operator()).isEqualTo(RecordValueConditionOperator.NOT);
    assertThat(check.condition().children().getFirst().values().getFirst().canonicalValue()).isEqualTo("active");
    assertThatThrownBy(() -> payloads.compile(page, Map.of("name", "retired"), Map.of(), FormRecordState.DRAFT, FormRecordState.DRAFT))
        .isInstanceOf(ApplicationFormException.class);
    payloads.compile(page, java.util.Collections.singletonMap("name", null), Map.of(), FormRecordState.DRAFT, FormRecordState.DRAFT);
  }

  @Test
  void keepsEditableDraftChoicesRepairableAndCompilesAllTwoHundredExactChoices() {
    var choices = IntStream.range(0, 200).mapToObj(index -> new FormChoiceOption(Long.toString(9223372036854775600L + index),
        "Choice " + index)).toList();
    var field = new FormFieldDefinition("serial", "serial", "Serial", "Serial", FormFieldType.INT64, FormStorageMode.STORED,
        false, false, null, null, null, null, choices, FormFieldWritePolicy.EDITABLE);
    var page = document(List.of(field), List.of());
    payloads.compile(page, Map.of("serial", "1"), Map.of(), FormRecordState.DRAFT, FormRecordState.DRAFT);
    assertThatThrownBy(() -> payloads.compile(page, Map.of("serial", "1"), Map.of(), FormRecordState.DRAFT, FormRecordState.SUBMITTED))
        .isInstanceOf(ApplicationFormException.class);
    var plan = FormPublicationRequirementsCompiler.compile("model", "app", "form",
        List.of(new Version(SCHEMA, page), new Version(SCHEMA, page)), page);
    assertThat(plan.valueChecks()).hasSize(1);
    var check = plan.valueChecks().values().iterator().next();
    assertThat(check.filter().operator()).isEqualTo(RecordFilterOperator.AND);
    assertThat(check.filter().children().get(1).values()).containsExactly("SUBMITTED");
    var alternatives = check.condition().children().getFirst();
    assertThat(alternatives.operator()).isEqualTo(RecordValueConditionOperator.OR);
    assertThat(alternatives.children()).hasSize(2).allSatisfy(batch -> assertThat(batch.values()).hasSize(100));
    assertThat(alternatives.children().getLast().values().getLast().canonicalValue()).isEqualTo("9223372036854775799");
  }

  @Test
  void refusesOversizedPlansInsteadOfSilentlySkippingRequirements() {
    var fields = IntStream.range(0, 102).mapToObj(index -> field("f" + index, false, FormFieldWritePolicy.CREATE_ONLY)).toList();
    var rules = IntStream.range(1, 102).mapToObj(index -> required("f" + index, equals("f0", "case-" + index))).toList();
    var page = document(fields, rules);
    assertThatThrownBy(() -> compile(List.of(new Version(SCHEMA, page)), page))
        .isInstanceOfSatisfying(ApplicationFormException.class, failure ->
            assertThat(failure.errorCode()).isEqualTo(ApplicationFormErrorCode.DOCUMENT_INVALID));
    assertThatThrownBy(() -> compile(List.of(), page)).isInstanceOf(ApplicationFormException.class);
  }

  private static Map<String, RecordCompletenessQuery> compile(final List<Version> pages, final ApplicationFormDocument governing) {
    return FormPublicationRequirementsCompiler.compile("model", "app", "form", pages, governing).checks();
  }

  private static FormCondition equals(final String field, final String value) {
    return new FormCondition(FormConditionOperator.EQUALS, field, List.of(new FormValue(FormValueKind.STRING, value)), List.of());
  }

  private static FormRuleDefinition required(final String field, final FormCondition condition) {
    return new FormRuleDefinition("required-" + field, "input-" + field, FormRuleEffect.REQUIRE, condition);
  }

  private static FormFieldDefinition field(final String id, final boolean required, final FormFieldWritePolicy policy) {
    return new FormFieldDefinition(id, "column_" + id, id, id, FormFieldType.STRING, FormStorageMode.STORED,
        required, false, 128, null, null, null, List.of(), policy);
  }

  private static ApplicationFormDocument document(final List<FormFieldDefinition> fields, final List<FormRuleDefinition> rules) {
    var children = fields.stream().map(field -> new FormComponentDefinition("input-" + field.fieldId(), "field.text", "1",
        field.fieldId(), 24, null, null, null, null, false, Map.of(), List.of())).toList();
    var root = new FormComponentDefinition("root", "layout.form", "1", null, 24, null, null, null, null, false, Map.of(), children);
    return new ApplicationFormDocument("1", fields, List.of(), List.of(
        new FormSceneDefinition("main", "main", "Main", FormSceneType.MAIN, null, root)), rules);
  }
}
