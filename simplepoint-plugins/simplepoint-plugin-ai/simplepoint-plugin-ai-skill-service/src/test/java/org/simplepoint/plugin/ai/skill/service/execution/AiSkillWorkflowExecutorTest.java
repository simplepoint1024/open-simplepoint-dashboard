package org.simplepoint.plugin.ai.skill.service.execution;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallResult;
import org.simplepoint.plugin.ai.mcp.api.model.McpWorkflowToolCallRequest;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpWorkflowToolExecutionService;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStepStatus;
import org.simplepoint.plugin.ai.skill.api.properties.SkillExecutionProperties;
import org.simplepoint.plugin.ai.skill.service.execution.AiSkillExecutionCoordinator.ExecutionTask;
import org.simplepoint.plugin.ai.skill.service.execution.AiSkillExecutionCoordinator.StepTask;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowTemplateResolver;

@ExtendWith(MockitoExtension.class)
class AiSkillWorkflowExecutorTest {

  @Mock
  private AiSkillExecutionCoordinator coordinator;

  @Mock
  private AiMcpWorkflowToolExecutionService toolExecutionService;

  private AiSkillWorkflowExecutor executor;

  @BeforeEach
  void setUp() {
    executor = new AiSkillWorkflowExecutor(
        coordinator,
        toolExecutionService,
        new SkillWorkflowTemplateResolver(),
        new SkillJsonSchemaValidator(),
        new SkillExecutionProperties(),
        new ObjectMapper()
    );
  }

  @Test
  void callsExactlyPinnedToolAndCheckpointsSuccess() {
    when(toolExecutionService.callWorkflowTool(any())).thenReturn(
        new McpGatewayToolCallResult(
            List.of(Map.of("type", "text", "text", "ok")),
            false,
            Map.of("message", "ok"),
            Map.of()
        )
    );

    ExecutionTask task = task();
    executor.execute(task);

    ArgumentCaptor<McpWorkflowToolCallRequest> request =
        ArgumentCaptor.forClass(McpWorkflowToolCallRequest.class);
    verify(toolExecutionService).callWorkflowTool(request.capture());
    org.assertj.core.api.Assertions.assertThat(request.getValue())
        .satisfies(value -> {
          org.assertj.core.api.Assertions.assertThat(value.serverId())
              .isEqualTo("server-a");
          org.assertj.core.api.Assertions.assertThat(value.snapshotId())
              .isEqualTo("snapshot-a");
          org.assertj.core.api.Assertions.assertThat(value.toolName())
              .isEqualTo("echo");
          org.assertj.core.api.Assertions.assertThat(value.arguments())
              .isEqualTo(Map.of("message", "hello"));
        });
    verify(coordinator).beginStep(
        eq(task),
        eq("echo-step"),
        eq("{\"message\":\"hello\"}")
    );
    verify(coordinator).completeStep(
        eq(task),
        eq("echo-step"),
        contains("\"structuredContent\":{\"message\":\"ok\"}")
    );
    verify(coordinator).succeed(
        eq(task),
        eq("{\"message\":\"ok\"}")
    );
  }

  @Test
  void resumesFromSuccessfulStepCheckpointWithoutCallingToolAgain() {
    ExecutionTask source = task();
    StepTask original = source.steps().getFirst();
    ExecutionTask resumed = new ExecutionTask(
        source.executionId(),
        source.scopeType(),
        source.tenantId(),
        source.requestedBy(),
        source.inputJson(),
        source.outputTemplateJson(),
        source.outputSchemaJson(),
        source.workerId(),
        source.leaseToken(),
        List.of(new StepTask(
            original.stepId(),
            original.stepOrder(),
            SkillExecutionStepStatus.SUCCEEDED,
            original.serverId(),
            original.snapshotId(),
            original.toolName(),
            original.inputSchemaHash(),
            original.argumentsTemplateJson(),
            """
                {
                  "content": [],
                  "error": false,
                  "structuredContent": {"message": "cached"},
                  "meta": {}
                }
                """
        ))
    );

    executor.execute(resumed);

    verifyNoInteractions(toolExecutionService);
    verify(coordinator).succeed(
        resumed,
        "{\"message\":\"cached\"}"
    );
  }

  private static ExecutionTask task() {
    return new ExecutionTask(
        "execution-a",
        AiResourceScope.SYSTEM,
        null,
        "user-a",
        "{\"message\":\"hello\"}",
        null,
        """
            {
              "type": "object",
              "required": ["message"],
              "additionalProperties": false,
              "properties": {
                "message": {"type": "string"}
              }
            }
            """,
        "worker-a",
        1,
        List.of(new StepTask(
            "echo-step",
            0,
            SkillExecutionStepStatus.PENDING,
            "server-a",
            "snapshot-a",
            "echo",
            "a".repeat(64),
            null,
            null
        ))
    );
  }
}
