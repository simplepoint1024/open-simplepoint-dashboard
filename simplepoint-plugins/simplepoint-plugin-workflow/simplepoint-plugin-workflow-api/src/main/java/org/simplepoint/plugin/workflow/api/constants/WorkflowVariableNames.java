package org.simplepoint.plugin.workflow.api.constants;

import java.util.Set;

/** Names owned by the platform or the engine, never accepted as user-submitted variables. */
public final class WorkflowVariableNames {

  public static final Set<String> RESERVED = Set.of(
      "initiator", "workflowDefinitionId", "workflowVersion", "workflowTitle",
      "execution", "task", "processEngine", "processEngineConfiguration", "delegateTask",
      "class", "Runtime", "ProcessBuilder", "new", "true", "false", "null", "and", "or", "not",
      "eq", "ne", "gt", "lt", "ge", "le", "empty", "div", "mod", "instanceof",
      "__proto__", "constructor", "prototype"
  );

  /** Also rejects names that the expression policy treats case-insensitively. */
  public static boolean isReserved(final String name) {
    return name != null && RESERVED.stream().anyMatch(value -> value.equalsIgnoreCase(name));
  }

  private WorkflowVariableNames() {
  }
}
