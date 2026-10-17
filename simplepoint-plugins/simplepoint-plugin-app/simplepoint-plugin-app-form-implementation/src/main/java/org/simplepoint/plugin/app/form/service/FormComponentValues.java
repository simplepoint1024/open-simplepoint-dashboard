package org.simplepoint.plugin.app.form.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import org.simplepoint.plugin.app.form.api.model.*;
import org.simplepoint.plugin.app.form.api.error.*;
import org.simplepoint.plugin.app.form.component.StandardComponentsContributor;

/** Enforces declarative component domains on the server, including direct API writes. */
final class FormComponentValues {
  private static final ObjectMapper JSON = new ObjectMapper();
  private FormComponentValues() {}

  static void validateDefinition(ApplicationFormDocument document) {
    Map<String, Object> domains = new java.util.HashMap<>();
    document.scenes().forEach(scene -> domain(scene.root(), domains));
    Map<String, Object> defaults = new java.util.HashMap<>();
    document.fields().stream().filter(field -> field.defaultValue() != null).forEach(field ->
        defaults.put(field.fieldId(), FormRecordPayloadCompiler.value(field.defaultValue())));
    try {validate(document, defaults);} catch (ApplicationFormException error) {
      throw new ApplicationFormException(ApplicationFormErrorCode.DOCUMENT_INVALID, "Default value is outside component options");
    }
  }

  private static void domain(FormComponentDefinition node, Map<String, Object> domains) {
    Object value = switch (node.componentType()) {
      case "field.rating" -> List.of(node.componentType(), Integer.parseInt(node.properties().get("count").value()));
      case "field.transfer", "field.cascader" -> List.of(node.componentType(), StandardComponentsContributor.options(node));
      default -> null;
    };
    if (value != null) {
      Object previous = domains.putIfAbsent(node.fieldId(), value);
      if (previous != null && !previous.equals(value)) throw new ApplicationFormException(
          ApplicationFormErrorCode.DOCUMENT_INVALID, "Reused fields must share component options and limits");
    }
    node.children().forEach(child -> domain(child, domains));
  }

  static void validate(ApplicationFormDocument document, Map<String, Object> values) {
    document.scenes().forEach(scene -> validate(scene.root(), values));
  }

  private static void validate(FormComponentDefinition node, Map<String, Object> values) {
    Object value = node.fieldId() == null ? null : values.get(node.fieldId());
    if (value != null && !"".equals(value)) {
      switch (node.componentType()) {
        case "field.rating" -> {
          try {
            int number = new BigDecimal(value.toString()).intValueExact();
            int max = Integer.parseInt(node.properties().get("count").value());
            if (number < 0 || number > max) fail(node);
          } catch (NumberFormatException | ArithmeticException error) {fail(node);}
        }
        case "field.transfer", "field.cascader" -> selection(node, value);
        default -> { }
      }
    }
    node.children().forEach(child -> validate(child, values));
  }

  private static void selection(FormComponentDefinition node, Object value) {
    try {
      if (!(value instanceof String)) fail(node);
      JsonNode selected = JSON.readTree(value.toString());
      if (!selected.isArray() || selected.size() > 200) fail(node);
      JsonNode options = StandardComponentsContributor.options(node);
      var seen = new HashSet<String>();
      for (JsonNode key : selected) {
        if (!key.isTextual()) fail(node);
        JsonNode match = null;
        for (JsonNode candidate : options) {
          if (candidate.path("value").asText().equals(key.asText())) {match = candidate; break;}
        }
        if (match == null) fail(node);
        if (node.componentType().equals("field.cascader")) options = match.path("children");
        else if (!seen.add(key.asText())) fail(node);
      }
      if (node.componentType().equals("field.cascader") && !selected.isEmpty() && !options.isEmpty() && !options.isMissingNode()) fail(node);
    } catch (java.io.IOException error) {fail(node);}
  }

  private static void fail(FormComponentDefinition node) {
    throw new ApplicationFormException(ApplicationFormErrorCode.RECORD_INVALID, "Field value is outside component options: " + node.fieldId());
  }
}
