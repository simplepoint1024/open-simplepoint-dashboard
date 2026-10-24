package org.simplepoint.plugin.app.form.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.component.StandardComponentsContributor;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFieldConstraints;
import org.simplepoint.plugin.dna.runtime.api.record.RecordJsonSelection;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationCondition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationField;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueConditionOperator;

/** Adapts bounded component domains to neutral result validation; UI labels stay in APP. */
final class FormCalculationComponentConstraints {
  private static final ObjectMapper JSON = new ObjectMapper();

  private FormCalculationComponentConstraints() {
  }

  static List<RecordValidationRule> compile(final ApplicationFormDocument document) {
    Map<String, FormFieldDefinition> fields = new LinkedHashMap<>();
    document.fields().forEach(field -> fields.put(field.fieldId(), field));
    Map<String, RecordValidationRule> rules = new LinkedHashMap<>();
    document.scenes().forEach(scene -> collect(scene.root(), fields, rules));
    return List.copyOf(rules.values());
  }

  private static void collect(final FormComponentDefinition node, final Map<String, FormFieldDefinition> fields,
      final Map<String, RecordValidationRule> rules) {
    var field = fields.get(node.fieldId());
    if (field != null && field.persistent()) {
      var reference = new RecordValidationField("parent", field.fieldId());
      RecordValidationRule rule = switch (node.componentType()) {
        case "field.rating" -> new RecordValidationRule("component", RecordValidationRule.Kind.RANGE, reference, null, null,
            "0", node.properties().get("count").value(),
            new RecordValidationCondition(RecordValueConditionOperator.IS_NOT_EMPTY, reference, List.of(), List.of()));
        case "field.cascader", "field.transfer" -> selection(node, reference);
        default -> null;
      };
      if (rule != null) {
        try {
          String id = FormCalculationValidationProjection.identity("component", JSON.writeValueAsString(rule));
          rules.putIfAbsent(id, new RecordValidationRule(id, rule.kind(), rule.field(), rule.other(), rule.detailId(),
              rule.minimum(), rule.maximum(), rule.when(), rule.constraints()));
        } catch (JsonProcessingException failure) {
          throw new IllegalArgumentException("Cannot encode published component domain identity", failure);
        }
      }
    }
    node.children().forEach(child -> collect(child, fields, rules));
  }

  private static RecordValidationRule selection(final FormComponentDefinition node, final RecordValidationField field) {
    List<List<String>> paths = new ArrayList<>();
    leaves(StandardComponentsContributor.options(node), List.of(), paths);
    var domain = new RecordJsonSelection(node.componentType().equals("field.transfer")
        ? RecordJsonSelection.Mode.SET : RecordJsonSelection.Mode.PATH, paths);
    return new RecordValidationRule("component", RecordValidationRule.Kind.FIELD, field, null, null, null, null,
        new RecordValidationCondition(RecordValueConditionOperator.TRUE, null, List.of(), List.of()),
        new RecordFieldConstraints(null, null, null, List.of(), domain));
  }

  private static void leaves(final JsonNode options, final List<String> prefix, final List<List<String>> paths) {
    for (var option : options) {
      var path = new ArrayList<>(prefix);
      path.add(option.path("value").textValue());
      var children = option.path("children");
      if (children.isMissingNode() || children.isEmpty()) {
        paths.add(List.copyOf(path));
      } else {
        leaves(children, path, paths);
      }
    }
  }
}
