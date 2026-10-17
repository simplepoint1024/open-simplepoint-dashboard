package org.simplepoint.plugin.app.form.api.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Process identity is supplied by the engine, never by browser variables. */
public record FormWorkflowSubmissionCheck(
    String tenantId,
    String actorId,
    String definitionId,
    int definitionVersion,
    String processInstanceId,
    String businessKey,
    String formId,
    int formVersion,
    String stage,
    String taskDefinitionKey,
    Map<String, Object> variables,
    boolean administrator,
    java.util.Set<String> resources) {
  /** Validates and defensively copies the immutable request or snapshot boundary. */
  public FormWorkflowSubmissionCheck {
    if (tenantId == null
        || tenantId.isBlank()
        || tenantId.length() > 64
        || actorId == null
        || actorId.isBlank()
        || actorId.length() > 64
        || definitionId == null
        || definitionId.length() > 64
        || definitionVersion < 1
        || businessKey == null
        || !businessKey.matches("app-form:[a-f0-9]{64}")
        || formId == null
        || formId.length() > 36
        || formVersion < 1
        || !java.util.Set.of("start", "task").contains(stage)
        || "task".equals(stage) && (processInstanceId == null || taskDefinitionKey == null)) {
      throw new IllegalArgumentException("Invalid workflow form submission context");
    }
    resources = resources == null ? java.util.Set.of() : java.util.Set.copyOf(resources);
    if (resources.size() > 512
        || resources.stream().anyMatch(value -> value.isBlank() || value.length() > 200)) {
      throw new IllegalArgumentException("Invalid effective form submission resources");
    }
    variables =
        variables == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    if (variables.size() > 100) {
      throw new IllegalArgumentException("Workflow variable budget exceeded");
    }
  }
}
