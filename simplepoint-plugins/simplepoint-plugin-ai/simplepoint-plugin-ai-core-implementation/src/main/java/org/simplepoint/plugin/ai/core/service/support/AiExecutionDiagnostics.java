package org.simplepoint.plugin.ai.core.service.support;

import java.util.Set;

/**
 * Normalizes durable Agent and Workflow diagnostics to public stable codes.
 *
 * <p>Execution diagnostics are returned to users and can outlive the runtime
 * process that produced them. They must therefore never retain exception
 * messages, provider response bodies, URLs, credentials, or localized prose.
 * The stable code is deliberately repeated as the legacy error message so
 * older clients remain compatible without receiving sensitive text.</p>
 */
public final class AiExecutionDiagnostics {

  /** Stable fallback for an unknown Agent execution failure. */
  public static final String AGENT_EXECUTION_FAILED =
      "AGENT_EXECUTION_FAILED";

  /** Stable fallback for an unknown Agent trace failure. */
  public static final String AGENT_TRACE_FAILED = "AGENT_RUNTIME_FAILED";

  /** Stable fallback for an unknown Workflow execution failure. */
  public static final String WORKFLOW_EXECUTION_FAILED =
      "WORKFLOW_EXECUTION_FAILED";

  /** Stable fallback for an unknown Workflow node failure. */
  public static final String WORKFLOW_NODE_FAILED = "WORKFLOW_NODE_FAILED";

  private static final Set<String> AGENT_CODES = Set.of(
      AGENT_EXECUTION_FAILED,
      "AGENT_BUDGET_COST_EXCEEDED",
      "AGENT_BUDGET_INPUT_TOKENS_EXCEEDED",
      "AGENT_BUDGET_LOOP_DEPTH_EXCEEDED",
      "AGENT_BUDGET_OUTPUT_TOKENS_EXCEEDED",
      "AGENT_BUDGET_STEPS_EXCEEDED",
      "AGENT_EXECUTION_CANCELLED",
      "AGENT_EXECUTION_RETRY_EXHAUSTED",
      "AGENT_HUMAN_INTERVENTION_TIMEOUT",
      "AGENT_MODEL_FALLBACK_EXHAUSTED",
      "AGENT_RUNTIME_FAILED",
      "AGENT_RUNTIME_LEASE_EXPIRED",
      "AGENT_RUNTIME_TRANSITION_LIMIT",
      "AGENT_SKILL_ARGUMENTS_INVALID",
      "AGENT_SKILL_EXECUTION_FAILED",
      "MODEL_INVOCATION_FAILED"
  );

  private static final Set<String> WORKFLOW_CODES = Set.of(
      WORKFLOW_EXECUTION_FAILED,
      WORKFLOW_NODE_FAILED,
      "WORKFLOW_AGENT_CHILD_CANCELLED",
      "WORKFLOW_AGENT_CHILD_FAILED",
      "WORKFLOW_AGENT_CHILD_REJECTED",
      "WORKFLOW_BUDGET_NODE_EXECUTIONS_EXCEEDED",
      "WORKFLOW_BUDGET_TIME_EXCEEDED",
      "WORKFLOW_COMPENSATION_FAILED",
      "WORKFLOW_COMPENSATION_CHILD_CANCELLED",
      "WORKFLOW_COMPENSATION_CHILD_FAILED",
      "WORKFLOW_COMPENSATION_CHILD_REJECTED",
      "WORKFLOW_HUMAN_TASK_TIMEOUT",
      "WORKFLOW_NODE_CANCELLED",
      "WORKFLOW_NODE_EXECUTION_FAILED",
      "WORKFLOW_RETRY_EXHAUSTED",
      "WORKFLOW_SKILL_CHILD_CANCELLED",
      "WORKFLOW_SKILL_CHILD_FAILED",
      "WORKFLOW_SKILL_CHILD_REJECTED",
      "WORKFLOW_WAIT_STATE_INVALID"
  );

  private AiExecutionDiagnostics() {
  }

  /**
   * Returns a safe Agent execution diagnostic.
   *
   * @param candidate untrusted persisted or runtime error code
   * @return a stable public diagnostic
   */
  public static StableDiagnostic agentExecution(final String candidate) {
    return diagnostic(candidate, AGENT_CODES, AGENT_EXECUTION_FAILED);
  }

  /**
   * Returns a safe Agent trace diagnostic.
   *
   * @param candidate untrusted persisted or runtime error code
   * @return a stable public diagnostic
   */
  public static StableDiagnostic agentTrace(final String candidate) {
    return diagnostic(candidate, AGENT_CODES, AGENT_TRACE_FAILED);
  }

  /**
   * Returns a safe Workflow execution diagnostic.
   *
   * @param candidate untrusted persisted or runtime error code
   * @return a stable public diagnostic
   */
  public static StableDiagnostic workflowExecution(
      final String candidate
  ) {
    return diagnostic(
        candidate,
        WORKFLOW_CODES,
        WORKFLOW_EXECUTION_FAILED
    );
  }

  /**
   * Returns a safe Workflow node diagnostic.
   *
   * @param candidate untrusted persisted or runtime error code
   * @return a stable public diagnostic
   */
  public static StableDiagnostic workflowNode(final String candidate) {
    return diagnostic(candidate, WORKFLOW_CODES, WORKFLOW_NODE_FAILED);
  }

  private static StableDiagnostic diagnostic(
      final String candidate,
      final Set<String> allowedCodes,
      final String fallback
  ) {
    String normalized = candidate == null ? null : candidate.trim();
    String code = normalized != null && allowedCodes.contains(normalized)
        ? normalized : fallback;
    return new StableDiagnostic(code, code);
  }

  /**
   * A language-neutral diagnostic safe for persistence and API responses.
   *
   * @param errorCode stable public error code
   * @param errorMessage compatibility message containing only the same code
   */
  public record StableDiagnostic(String errorCode, String errorMessage) {
  }
}
