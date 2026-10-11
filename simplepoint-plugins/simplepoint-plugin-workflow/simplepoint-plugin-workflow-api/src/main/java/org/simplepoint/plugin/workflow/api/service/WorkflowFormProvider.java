package org.simplepoint.plugin.workflow.api.service;

import java.util.Map;
import org.simplepoint.plugin.workflow.api.model.WorkflowFormContract.Binding;

/** Server-side counterpart to the host's form adapter; never loaded from user-controlled URLs. */
public interface WorkflowFormProvider {

  /** Stable provider identifier used by persisted version-pinned bindings. */
  String provider();

  /** Validates mapped variables against the bound immutable form before the engine can advance. */
  void validateSubmission(Binding binding, String stage, String taskDefinitionKey, Map<String, Object> variables);
}
