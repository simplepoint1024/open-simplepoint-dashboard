package org.simplepoint.plugin.app.form.api.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computed outputs only, in the precision-safe form wire representation and original detail order.
 */
public record FormCalculationPreview(
    Map<String, Object> values, Map<String, List<Map<String, Object>>> details) {
  /** Does not expose mutable maps retained by an evaluator. */
  public FormCalculationPreview {
    values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    Map<String, List<Map<String, Object>>> copy = new LinkedHashMap<>();
    details.forEach(
        (id, rows) ->
            copy.put(
                id,
                rows.stream()
                    .map(row -> Collections.unmodifiableMap(new LinkedHashMap<>(row)))
                    .toList()));
    details = Collections.unmodifiableMap(copy);
  }
}
