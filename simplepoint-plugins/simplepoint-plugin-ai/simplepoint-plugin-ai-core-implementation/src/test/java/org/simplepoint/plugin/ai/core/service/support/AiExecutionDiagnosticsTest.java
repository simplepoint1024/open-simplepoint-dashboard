package org.simplepoint.plugin.ai.core.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.service.support.AiExecutionDiagnostics.StableDiagnostic;

class AiExecutionDiagnosticsTest {

  @Test
  void shouldPreserveOnlyRegisteredStableCodes() {
    StableDiagnostic agent = AiExecutionDiagnostics.agentExecution(
        " AGENT_BUDGET_STEPS_EXCEEDED "
    );
    StableDiagnostic workflow = AiExecutionDiagnostics.workflowNode(
        "WORKFLOW_AGENT_CHILD_FAILED"
    );
    StableDiagnostic historicalCompensation =
        AiExecutionDiagnostics.workflowNode(
            "WORKFLOW_COMPENSATION_FAILED"
        );

    assertThat(agent.errorCode())
        .isEqualTo("AGENT_BUDGET_STEPS_EXCEEDED");
    assertThat(agent.errorMessage()).isEqualTo(agent.errorCode());
    assertThat(workflow.errorCode())
        .isEqualTo("WORKFLOW_AGENT_CHILD_FAILED");
    assertThat(workflow.errorMessage()).isEqualTo(workflow.errorCode());
    assertThat(historicalCompensation.errorCode())
        .isEqualTo("WORKFLOW_COMPENSATION_FAILED");
  }

  @Test
  void shouldRejectUntrustedDiagnosticsWithoutRetainingTheirContent() {
    List<String> sentinels = List.of(
        "provider body: quota denied for sk-live-secret",
        "https://provider.example/v1?api_key=credential",
        "Bearer eyJhbGciOiJIUzI1NiJ9.secret.signature",
        "java.lang.IllegalStateException: internal-database-sentinel",
        "UNKNOWN_PROVIDER_FAILURE"
    );

    sentinels.forEach(sentinel -> {
      StableDiagnostic agent =
          AiExecutionDiagnostics.agentExecution(sentinel);
      StableDiagnostic agentTrace =
          AiExecutionDiagnostics.agentTrace(sentinel);
      StableDiagnostic workflow =
          AiExecutionDiagnostics.workflowExecution(sentinel);
      StableDiagnostic workflowNode =
          AiExecutionDiagnostics.workflowNode(sentinel);

      assertThat(agent.errorCode()).isEqualTo("AGENT_EXECUTION_FAILED");
      assertThat(agentTrace.errorCode()).isEqualTo("AGENT_RUNTIME_FAILED");
      assertThat(workflow.errorCode())
          .isEqualTo("WORKFLOW_EXECUTION_FAILED");
      assertThat(workflowNode.errorCode())
          .isEqualTo("WORKFLOW_NODE_FAILED");
      assertThat(List.of(
          agent.errorCode(),
          agent.errorMessage(),
          agentTrace.errorCode(),
          agentTrace.errorMessage(),
          workflow.errorCode(),
          workflow.errorMessage(),
          workflowNode.errorCode(),
          workflowNode.errorMessage()
      )).allSatisfy(value -> assertThat(value).doesNotContain(sentinel));
    });
  }
}
