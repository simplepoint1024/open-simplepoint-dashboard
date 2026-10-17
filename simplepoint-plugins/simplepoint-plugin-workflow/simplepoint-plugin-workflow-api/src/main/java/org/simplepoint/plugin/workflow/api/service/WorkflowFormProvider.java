package org.simplepoint.plugin.workflow.api.service;

import java.util.Map;
import org.simplepoint.plugin.workflow.api.model.WorkflowFormContract.Binding;

/** Server-side counterpart to the host's form adapter; never loaded from user-controlled URLs. */
public interface WorkflowFormProvider {

  /** Stable provider identifier used by persisted version-pinned bindings. */
  String provider();

  /** Checks an immutable binding before a process version can be published. */
  default void validateBinding(
      Binding binding,
      String stage,
      String taskDefinitionKey,
      org.simplepoint.plugin.workflow.api.model.WorkflowFormContract contract,
      String tenantId) {}

  /** Providers with durable business submissions require a unique business reference. */
  default boolean requiresBusinessReference() {
    return false;
  }

  /** Snapshot-backed providers keep mapped business facts immutable throughout approval. */
  default boolean protectsMappedVariables() {
    return false;
  }

  /**
   * Context-aware providers can validate the original accepted form intent and immutable snapshot.
   */
  default void validateSubmission(
      Binding binding,
      String stage,
      String taskDefinitionKey,
      Map<String, Object> variables,
      org.simplepoint.plugin.workflow.api.model.WorkflowFormContext context) {
    validateSubmission(binding, stage, taskDefinitionKey, variables);
  }

  /** Validates mapped variables against the bound immutable form before the engine can advance. */
  void validateSubmission(
      Binding binding, String stage, String taskDefinitionKey, Map<String, Object> variables);
}
