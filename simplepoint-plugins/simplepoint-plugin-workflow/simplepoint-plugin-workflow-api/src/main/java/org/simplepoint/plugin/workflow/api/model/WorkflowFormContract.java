package org.simplepoint.plugin.workflow.api.model;

import java.util.List;
import java.util.Map;

/** Versioned variable and external form-engine boundary, snapshotted with each process version. */
public record WorkflowFormContract(
    int schemaVersion,
    List<Variable> variables,
    Binding startBinding,
    Map<String, Binding> taskBindings) {

  /** A scalar or directory-backed process variable and the stages allowed to edit it. */
  public record Variable(
      String name,
      String label,
      String type,
      String scope,
      boolean required,
      Object defaultValue) {}

  /**
   * Pins an external form version; mapping keys are form fields and values are declared variables.
   */
  public record Binding(
      String provider,
      String formId,
      int version,
      Map<String, String> variableMappings,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          String sceneId) {
    /** Preserves compatibility for callers without optional snapshot or scene metadata. */
    public Binding(
        String provider, String formId, int version, Map<String, String> variableMappings) {
      this(provider, formId, version, variableMappings, null);
    }
  }

  /** An explicit empty v1 contract keeps legacy BPMN processes usable. */
  public static WorkflowFormContract empty() {
    return new WorkflowFormContract(1, List.of(), null, Map.of());
  }
}
