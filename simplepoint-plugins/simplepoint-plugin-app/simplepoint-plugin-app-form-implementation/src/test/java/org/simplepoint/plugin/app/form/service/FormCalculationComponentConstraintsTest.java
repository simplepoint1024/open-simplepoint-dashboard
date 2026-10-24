package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.model.*;
import org.simplepoint.plugin.dna.runtime.api.record.RecordJsonSelection;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueConditionOperator;

class FormCalculationComponentConstraintsTest {
  @Test
  void freezesComponentDomainsForCalculatedFieldsAndSurvivingPagesWithoutUiMetadata() throws Exception {
    var document = document("显示名称");
    var encoded = new FormDocumentCodec(new ObjectMapper()).encode(document);
    var rules = FormCalculationComponentConstraints.compile(document);
    assertThat(rules).hasSize(3);
    assertThat(rules).anySatisfy(rule -> {
      assertThat(rule.field().fieldId()).isEqualTo("rating");
      assertThat(rule.kind()).isEqualTo(RecordValidationRule.Kind.RANGE);
      assertThat(rule.minimum()).isEqualTo("0");
      assertThat(rule.maximum()).isEqualTo("5");
      assertThat(rule.when().operator()).isEqualTo(RecordValueConditionOperator.IS_NOT_EMPTY);
    });
    assertThat(rules).anySatisfy(rule -> {
      assertThat(rule.field().fieldId()).isEqualTo("selection");
      assertThat(rule.constraints().selection().mode()).isEqualTo(RecordJsonSelection.Mode.PATH);
      assertThat(rule.constraints().selection().paths()).containsExactly(List.of("中国", "上海"), List.of("日本", "東京"));
    });
    assertThat(rules).anySatisfy(rule -> {
      assertThat(rule.field().fieldId()).isEqualTo("transfer");
      assertThat(rule.constraints().selection().mode()).isEqualTo(RecordJsonSelection.Mode.SET);
      assertThat(rule.constraints().selection().paths()).containsExactly(List.of("A"), List.of("B"));
    });
    assertThat(FormCalculationValidationProjection.compile(document)).containsAll(rules);
    assertThat(FormCalculationValidationProjection.pageRequirements(document)).containsAll(rules);
    assertThat(new FormDocumentCodec(new ObjectMapper()).encode(document)).isEqualTo(encoded);
    assertThat(new ObjectMapper().writeValueAsString(rules)).doesNotContain("显示名称", "column_", "field.cascader", "field.transfer");
    assertThat(FormCalculationComponentConstraints.compile(document("Renamed label"))).isEqualTo(rules);
  }

  @Test
  void repeatedSceneUsageSharesOneStableDomainRule() {
    var document = document("label");
    var reused = new ApplicationFormDocument("1", document.fields(), List.of(),
        List.of(document.scenes().getFirst(), new FormSceneDefinition("dialog", "dialog", "Dialog", FormSceneType.DIALOG, null,
            node("dialog-root", "layout.form", null, Map.of(), List.of(node("duplicate-rating", "field.rating", "rating",
                Map.of("count", new FormValue(FormValueKind.INTEGER, "5")), List.of()))))), List.of());
    assertThat(FormCalculationComponentConstraints.compile(reused)).isEqualTo(FormCalculationComponentConstraints.compile(document));
  }

  private static ApplicationFormDocument document(String label) {
    var properties = Map.of("options", new FormValue(FormValueKind.JSON, """
        [{"value":"中国","label":"%s","children":[{"value":"上海","label":"%s"}]},
         {"value":"日本","label":"%s","children":[{"value":"東京","label":"%s"}]}]
        """.formatted(label, label, label, label)));
    var rating = node("rating-input", "field.rating", "rating", Map.of("count", new FormValue(FormValueKind.INTEGER, "5")), List.of());
    var selection = node("selection-input", "field.cascader", "selection", properties, List.of());
    var transfer = node("transfer-input", "field.transfer", "transfer", Map.of("options", new FormValue(FormValueKind.JSON,
        "[{\"value\":\"A\",\"label\":\"A\"},{\"value\":\"B\",\"label\":\"B\"}]")), List.of());
    return new ApplicationFormDocument("1", List.of(field("rating", FormFieldType.INT32), field("selection", FormFieldType.JSON),
        field("transfer", FormFieldType.JSON)), List.of(), List.of(new FormSceneDefinition("main", "main", label, FormSceneType.MAIN, null,
            node("root", "layout.form", null, Map.of(), List.of(rating, selection, transfer)))), List.of());
  }

  private static FormFieldDefinition field(String id, FormFieldType type) {
    return new FormFieldDefinition(id, "column_" + id, id, id, type, FormStorageMode.COMPUTED, false, false, null, null, null,
        null, List.of(), null, null, null, new FormCalculation("1", type == FormFieldType.INT32 ? "5" : "\"[]\""));
  }

  private static FormComponentDefinition node(String id, String type, String field, Map<String, FormValue> properties,
      List<FormComponentDefinition> children) {
    return new FormComponentDefinition(id, type, "1", field, 24, null, null, null, null, false, properties, children);
  }
}
