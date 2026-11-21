package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormCondition;
import org.simplepoint.plugin.app.form.api.model.FormConditionOperator;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationQualification;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationQualification.Recheck;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueConditionOperator;

class FormRelationQualificationTest {
  private final FormDocumentCodec codec = new FormDocumentCodec(new ObjectMapper());

  @Test
  void legacyAbsenceAndQualificationBothSurviveImmutableSerialization() {
    var original = FormRelationPublicationProjectionTest.document();
    assertThat(codec.encode(original).json()).doesNotContain("qualification");
    var configured = configure(original, Recheck.ON_SUBMIT);
    var encoded = codec.encode(configured);
    assertThat(codec.decode(encoded.json())).isEqualTo(configured);
    assertThat(encoded.checksum()).isNotEqualTo(codec.encode(original).checksum());
    assertThat(encoded.checksum())
        .isNotEqualTo(codec.encode(configure(original, Recheck.ALWAYS)).checksum());
    var frozen =
        codec.encodeRelationBindings(
            original, FormRelationPublicationProjectionTest.bindings(original));
    assertThatThrownBy(() -> codec.decodeRelationBindings(configured, frozen))
        .isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void targetPredicatesAndSourceRecheckUseSeparateStableScopesIncludingDetailReferences() {
    var document = configure(FormRelationPublicationProjectionTest.document(), Recheck.ON_SUBMIT);
    var rules =
        FormRelationPublicationProjection.rules(
            document, FormRelationPublicationProjectionTest.bindings(document));
    assertThat(rules)
        .hasSize(2)
        .allSatisfy(
            rule -> {
              assertThat(rule.eligibility().field().scope()).isEqualTo("parent");
              assertThat(rule.eligibility().field().fieldId()).isEqualTo("active");
              assertThat(rule.eligibility().values().getFirst().canonicalValue()).isEqualTo("true");
              assertThat(rule.recheckWhen().field().scope()).isEqualTo("parent");
              assertThat(rule.recheckWhen().field().fieldId())
                  .isEqualTo(FormStorageProjectionCompiler.RECORD_STATE_FIELD_ID);
              assertThat(rule.recheckWhen().values().getFirst().canonicalValue())
                  .isEqualTo("SUBMITTED");
            });
    assertThat(rules.stream().map(rule -> rule.source().scope()))
        .containsExactlyInAnyOrder("parent", "lines");
    assertThat(
            FormRelationQualificationProjection.recheck(
                new FormRelationQualification(active(), Recheck.ON_CHANGE)))
        .isNull();
    assertThat(
            FormRelationQualificationProjection.recheck(
                    new FormRelationQualification(active(), Recheck.ALWAYS))
                .operator())
        .isEqualTo(RecordValueConditionOperator.TRUE);
  }

  @Test
  void targetFieldsMustExistBePersistentAndMatchTheLiteralAndOperator() {
    FormDocumentValidator.validateRelationCondition(
        active(), List.of(field(FormFieldType.BOOLEAN, FormStorageMode.STORED)));
    for (var fields :
        List.of(
            List.<FormFieldDefinition>of(),
            List.of(field(FormFieldType.STRING, FormStorageMode.STORED)),
            List.of(field(FormFieldType.BOOLEAN, FormStorageMode.TRANSIENT)))) {
      assertThatThrownBy(() -> FormDocumentValidator.validateRelationCondition(active(), fields))
          .isInstanceOf(ApplicationFormException.class);
    }
    var ordered =
        new FormCondition(
            FormConditionOperator.GREATER_THAN, "active", active().values(), List.of());
    assertThatThrownBy(
            () ->
                FormDocumentValidator.validateRelationCondition(
                    ordered, List.of(field(FormFieldType.BOOLEAN, FormStorageMode.STORED))))
        .isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void invalidShapesAndBudgetsAreRejectedBeforeProjection() {
    var tooDeep = active();
    for (int i = 0; i < 4; i++) {
      tooDeep = new FormCondition(FormConditionOperator.NOT, null, List.of(), List.of(tooDeep));
    }
    var deep = tooDeep;
    for (var condition :
        List.of(
            deep,
            new FormCondition(
                FormConditionOperator.OR,
                null,
                List.of(),
                java.util.Collections.nCopies(32, active())),
            new FormCondition(FormConditionOperator.EQUALS, "active", List.of(), List.of()),
            new FormCondition(
                FormConditionOperator.EQUALS, "active", active().values(), List.of(active())),
            new FormCondition(
                FormConditionOperator.NOT, null, List.of(), List.of(active(), active())),
            new FormCondition(
                FormConditionOperator.EQUALS,
                "active",
                List.of(new FormValue(FormValueKind.STRING, "x".repeat(2001))),
                List.of()))) {
      assertThatThrownBy(() -> new FormRelationQualification(condition, Recheck.ON_CHANGE))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  static ApplicationFormDocument configure(ApplicationFormDocument document, Recheck recheck) {
    var relations =
        document.relations().stream()
            .map(
                relation ->
                    new FormRelationDefinition(
                        relation.contractVersion(),
                        relation.relationId(),
                        relation.cardinality(),
                        relation.sourceField(),
                        relation.target(),
                        new FormRelationQualification(active(), recheck)))
            .toList();
    return new ApplicationFormDocument(
        document.contractVersion(),
        document.fields(),
        document.indexes(),
        document.scenes(),
        document.rules(),
        document.details(),
        document.constraints(),
        document.presentation(),
        relations);
  }

  static FormCondition active() {
    return new FormCondition(
        FormConditionOperator.EQUALS,
        "active",
        List.of(new FormValue(FormValueKind.BOOLEAN, "true")),
        List.of());
  }

  static FormFieldDefinition field(FormFieldType type, FormStorageMode mode) {
    return new FormFieldDefinition(
        "active",
        "is_active",
        "Active",
        "Active",
        type,
        mode,
        false,
        false,
        null,
        null,
        null,
        null,
        List.of());
  }
}
