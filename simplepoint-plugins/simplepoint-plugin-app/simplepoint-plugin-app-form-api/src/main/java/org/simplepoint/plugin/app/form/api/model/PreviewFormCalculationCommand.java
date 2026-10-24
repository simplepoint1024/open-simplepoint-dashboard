package org.simplepoint.plugin.app.form.api.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Unsaved designer document and explicitly supplied sample data; never reads stored business data.
 */
public record PreviewFormCalculationCommand(
    ApplicationFormDocument document,
    Map<String, Object> values,
    Map<String, List<Map<String, Object>>> details) {
  /** Applies aggregate budgets before compilation and preserves explicit nullable input values. */
  public PreviewFormCalculationCommand {
    Objects.requireNonNull(document, "document");
    values = inputs(values == null ? Map.of() : values);
    details = details == null ? Map.of() : details;
    if (details.size() > 5) {
      throw new IllegalArgumentException("Too many calculation sample collections");
    }
    Map<String, List<Map<String, Object>>> copy = new LinkedHashMap<>();
    details.forEach(
        (id, rows) -> {
          if (id == null || id.isBlank() || id.length() > 128 || rows == null || rows.size() > 40) {
            throw new IllegalArgumentException("Invalid calculation sample collection");
          }
          copy.put(id, rows.stream().map(PreviewFormCalculationCommand::inputs).toList());
        });
    details = Collections.unmodifiableMap(copy);
  }

  private static Map<String, Object> inputs(Map<String, Object> source) {
    if (source == null
        || source.size() > 256
        || source.keySet().stream()
            .anyMatch(id -> id == null || id.isBlank() || id.length() > 128)) {
      throw new IllegalArgumentException("Invalid calculation sample fields");
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(source));
  }
}
