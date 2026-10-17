package org.simplepoint.plugin.app.form.api.model;

import java.util.Map;

/** Metadata-only validation requested by the allowlisted workflow publisher. */
public record FormWorkflowBindingCheck(
    String tenantId,
    String formId,
    int version,
    Map<String, String> fieldVariableTypes,
    String sceneId,
    String stage) {
  /** Validates and defensively copies the immutable request or snapshot boundary. */
  public FormWorkflowBindingCheck {
    if (tenantId == null
        || tenantId.isBlank()
        || tenantId.length() > 64
        || formId == null
        || formId.isBlank()
        || formId.length() > 36
        || version < 1) {
      throw new IllegalArgumentException("Invalid workflow form binding");
    }
    if (!java.util.Set.of("start", "task").contains(stage)
        || sceneId != null && (sceneId.isBlank() || sceneId.length() > 160)) {
      throw new IllegalArgumentException("Invalid workflow form scene");
    }
    fieldVariableTypes = fieldVariableTypes == null ? Map.of() : Map.copyOf(fieldVariableTypes);
    if (fieldVariableTypes.size() > 100
        || fieldVariableTypes.entrySet().stream()
            .anyMatch(
                entry ->
                    entry.getKey().isBlank()
                        || entry.getKey().length() > 160
                        || entry.getValue().length() > 32)) {
      throw new IllegalArgumentException("Invalid workflow field mappings");
    }
  }
}
