package org.simplepoint.plugin.ai.skill.service.execution;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayPromptGetResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayResourceReadResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallResult;
import org.simplepoint.plugin.ai.mcp.api.model.McpWorkflowPromptGetRequest;
import org.simplepoint.plugin.ai.mcp.api.model.McpWorkflowResourceReadRequest;
import org.simplepoint.plugin.ai.mcp.api.model.McpWorkflowToolCallRequest;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpWorkflowExecutionService;
import org.simplepoint.plugin.ai.skill.api.model.SkillDebugMode;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStepStatus;
import org.simplepoint.plugin.ai.skill.api.properties.SkillExecutionProperties;
import org.simplepoint.plugin.ai.skill.service.execution.AiSkillExecutionCoordinator.ExecutionTask;
import org.simplepoint.plugin.ai.skill.service.execution.AiSkillExecutionCoordinator.StepTask;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;
import org.simplepoint.plugin.ai.skill.service.support.SkillMockAssertionEvaluator;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowConditionEvaluator;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowTemplateResolver;

@ExtendWith(MockitoExtension.class)
class AiSkillWorkflowExecutorTest {

  @Mock
  private AiSkillExecutionCoordinator coordinator;

  @Mock
  private AiMcpWorkflowExecutionService toolExecutionService;

  private AiSkillWorkflowExecutor executor;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = new ObjectMapper();
    SkillExecutionProperties properties = new SkillExecutionProperties();
    properties.setCapabilityTokenSigningKey(
        "test-capability-signing-key-with-at-least-32-bytes"
    );
    SkillWorkflowTemplateResolver templateResolver =
        new SkillWorkflowTemplateResolver();
    SkillWorkflowConditionEvaluator conditionEvaluator =
        new SkillWorkflowConditionEvaluator(templateResolver);
    executor = new AiSkillWorkflowExecutor(
        coordinator,
        toolExecutionService,
        templateResolver,
        conditionEvaluator,
        new SkillWorkflowPlanCompiler(
            templateResolver,
            conditionEvaluator
        ),
        new SkillJsonSchemaValidator(),
        properties,
        new SkillCapabilityTokenIssuer(properties, objectMapper),
        new SkillMockAssertionEvaluator(objectMapper),
        objectMapper
    );
  }

  @Test
  void callsExactlyPinnedToolAndCheckpointsSuccess() {
    when(coordinator.beginStep(any(), any(), any(), any()))
        .thenReturn(true);
    when(coordinator.completeStep(any(), any(), any()))
        .thenReturn(true);
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
          org.assertj.core.api.Assertions.assertThat(value.skillId())
              .isEqualTo("skill-a");
          org.assertj.core.api.Assertions.assertThat(value.skillVersionId())
              .isEqualTo("version-a");
          org.assertj.core.api.Assertions.assertThat(value.capabilityToken())
              .isNotBlank();
        });
    verify(coordinator).beginStep(
        eq(task),
        eq("echo-step"),
        eq("{\"message\":\"hello\"}"),
        any()
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
  void executesPinnedMockWithoutCapabilityTokenOrExternalMcpCall() {
    when(coordinator.beginStep(any(), any(), any(), any()))
        .thenReturn(true);
    when(coordinator.completeStep(any(), any(), any()))
        .thenReturn(true);
    ExecutionTask source = task();
    ExecutionTask mockTask = new ExecutionTask(
        source.executionId(),
        source.skillId(),
        source.skillVersionId(),
        source.scopeType(),
        source.tenantId(),
        source.requestedBy(),
        SkillDebugMode.MOCK,
        """
            {"testCaseId":"happy-path","mocks":{"echo-step":{
              "nodeId":"echo-step","mode":"SUCCESS",
              "output":{"message":"mocked"}
            }},"assertions":[{
              "id":"message","sourceNodeId":"__output",
              "fieldPath":"message","operator":"EQUALS",
              "expected":"mocked"
            }]}
            """,
        "b".repeat(64),
        source.inputJson(),
        source.workflowPlanJson(),
        source.outputTemplateJson(),
        source.outputSchemaJson(),
        source.maximumToolCalls(),
        source.maximumDurationSeconds(),
        source.maximumPayloadBytes(),
        source.consumedToolCalls(),
        source.consumedPayloadBytes(),
        source.deadlineAt(),
        source.workerId(),
        source.leaseToken(),
        source.steps()
    );

    executor.execute(mockTask);

    verifyNoInteractions(toolExecutionService);
    verify(coordinator).beginStep(
        eq(mockTask),
        eq("echo-step"),
        eq("{\"message\":\"hello\"}"),
        eq("b".repeat(64))
    );
    verify(coordinator).completeStep(
        eq(mockTask),
        eq("echo-step"),
        contains("\"structuredContent\":{\"message\":\"mocked\"}")
    );
    verify(coordinator).completeMock(
        eq(mockTask),
        eq("{\"message\":\"mocked\"}"),
        contains("\"passed\":true"),
        eq(true)
    );
    verify(coordinator, never()).succeed(any(), any());
  }

  @Test
  void completesMockAsFailedWhenPinnedAssertionDoesNotMatch() {
    when(coordinator.beginStep(any(), any(), any(), any()))
        .thenReturn(true);
    when(coordinator.completeStep(any(), any(), any()))
        .thenReturn(true);
    ExecutionTask source = task();
    ExecutionTask mockTask = new ExecutionTask(
        source.executionId(), source.skillId(), source.skillVersionId(),
        source.scopeType(), source.tenantId(), source.requestedBy(),
        SkillDebugMode.MOCK,
        """
            {"testCaseId":"failure","mocks":{"echo-step":{
              "nodeId":"echo-step","mode":"SUCCESS",
              "output":{"message":"actual"}
            }},"assertions":[{
              "id":"message","sourceNodeId":"__output",
              "fieldPath":"message","operator":"EQUALS",
              "expected":"expected"
            }]}
            """,
        "d".repeat(64),
        source.inputJson(), source.workflowPlanJson(),
        source.outputTemplateJson(), source.outputSchemaJson(),
        source.maximumToolCalls(), source.maximumDurationSeconds(),
        source.maximumPayloadBytes(), source.consumedToolCalls(),
        source.consumedPayloadBytes(), source.deadlineAt(),
        source.workerId(), source.leaseToken(), source.steps()
    );

    executor.execute(mockTask);

    verifyNoInteractions(toolExecutionService);
    verify(coordinator).completeMock(
        eq(mockTask),
        eq("{\"message\":\"actual\"}"),
        contains("\"passed\":false"),
        eq(false)
    );
  }

  @Test
  void failsClosedWhenPinnedMockDoesNotContainTheStep() {
    when(coordinator.beginStep(any(), any(), any(), any()))
        .thenReturn(true);
    ExecutionTask source = task();
    ExecutionTask mockTask = new ExecutionTask(
        source.executionId(), source.skillId(), source.skillVersionId(),
        source.scopeType(), source.tenantId(), source.requestedBy(),
        SkillDebugMode.MOCK,
        "{\"testCaseId\":\"incomplete\",\"mocks\":{}}",
        "c".repeat(64),
        source.inputJson(), source.workflowPlanJson(),
        source.outputTemplateJson(), source.outputSchemaJson(),
        source.maximumToolCalls(), source.maximumDurationSeconds(),
        source.maximumPayloadBytes(), source.consumedToolCalls(),
        source.consumedPayloadBytes(), source.deadlineAt(),
        source.workerId(), source.leaseToken(), source.steps()
    );

    executor.execute(mockTask);

    verifyNoInteractions(toolExecutionService);
    verify(coordinator).fail(
        mockTask,
        "echo-step",
        "SKILL_MOCK_RESULT_MISSING",
        "Pinned MOCK result does not exist for workflow step"
    );
  }

  @Test
  void resumesFromSuccessfulStepCheckpointWithoutCallingToolAgain() {
    ExecutionTask source = task();
    StepTask original = source.steps().getFirst();
    ExecutionTask resumed = new ExecutionTask(
        source.executionId(),
        source.skillId(),
        source.skillVersionId(),
        source.scopeType(),
        source.tenantId(),
        source.requestedBy(),
        source.inputJson(),
        source.workflowPlanJson(),
        source.outputTemplateJson(),
        source.outputSchemaJson(),
        source.maximumToolCalls(),
        source.maximumDurationSeconds(),
        source.maximumPayloadBytes(),
        source.consumedToolCalls(),
        source.consumedPayloadBytes(),
        source.deadlineAt(),
        source.workerId(),
        source.leaseToken(),
        List.of(new StepTask(
            original.stepId(),
            original.stepOrder(),
            SkillExecutionStepStatus.SUCCEEDED,
            original.stepType(),
            original.capabilityAlias(),
            original.serverId(),
            original.snapshotId(),
            original.capabilityName(),
            original.capabilitySchemaHash(),
            original.capabilityTemplate(),
            original.inputTemplateJson(),
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

  @Test
  void stopsBeforeToolIoWhenPauseWinsTheCheckpoint() {
    when(coordinator.beginStep(any(), any(), any(), any()))
        .thenReturn(false);

    executor.execute(task());

    verifyNoInteractions(toolExecutionService);
  }

  @Test
  void executesOnlyTheSelectedConditionBranchAndSkipsTheOther() {
    when(coordinator.checkpoint(any())).thenReturn(true);
    when(coordinator.skipSteps(any(), any())).thenReturn(true);
    when(coordinator.beginStep(any(), any(), any(), any()))
        .thenReturn(true);
    when(coordinator.completeStep(any(), any(), any()))
        .thenReturn(true);
    when(toolExecutionService.callWorkflowTool(any())).thenReturn(
        result("selected")
    );
    String workflow = """
        {
          "steps": [
            {
              "id": "route",
              "type": "condition",
              "condition": {
                "equals": [
                  {"$ref": "input.mode"},
                  "full"
                ]
              },
              "then": [
                {
                  "id": "full",
                  "type": "tool",
                  "tool": "echo",
                  "arguments": {"message": "full"}
                }
              ],
              "else": [
                {
                  "id": "compact",
                  "type": "tool",
                  "tool": "echo",
                  "arguments": {"message": "compact"}
                }
              ]
            }
          ],
          "output": {
            "selected": {"$ref": "steps.route.selectedBranch"}
          }
        }
        """;
    ExecutionTask task = plannedTask(
        "{\"mode\":\"full\"}",
        workflow,
        "{\"selected\":{\"$ref\":\"steps.route.selectedBranch\"}}",
        List.of(step("full"), step("compact"))
    );

    executor.execute(task);

    ArgumentCaptor<McpWorkflowToolCallRequest> request =
        ArgumentCaptor.forClass(McpWorkflowToolCallRequest.class);
    verify(toolExecutionService).callWorkflowTool(request.capture());
    org.assertj.core.api.Assertions.assertThat(request.getValue().arguments())
        .isEqualTo(Map.of("message", "full"));
    verify(coordinator).skipSteps(task, List.of("compact"));
    verify(coordinator).succeed(task, "{\"selected\":\"then\"}");
  }

  @Test
  void resumesConditionFromSucceededAndSkippedLeafCheckpoints() {
    when(coordinator.checkpoint(any())).thenReturn(true);
    when(coordinator.skipSteps(any(), any())).thenReturn(true);
    String workflow = """
        {
          "steps": [
            {
              "id": "route",
              "type": "condition",
              "condition": {
                "equals": [
                  {"$ref": "input.mode"},
                  "full"
                ]
              },
              "then": [
                {"id": "full", "type": "tool", "tool": "echo"}
              ],
              "else": [
                {"id": "compact", "type": "tool", "tool": "echo"}
              ]
            }
          ],
          "output": {
            "message": {
              "$ref": "steps.full.structuredContent.message"
            }
          }
        }
        """;
    ExecutionTask task = plannedTask(
        "{\"mode\":\"full\"}",
        workflow,
        """
            {
              "message": {
                "$ref": "steps.full.structuredContent.message"
              }
            }
            """,
        List.of(
            step(
                "full",
                SkillExecutionStepStatus.SUCCEEDED,
                """
                    {
                      "content": [],
                      "error": false,
                      "structuredContent": {"message": "cached"},
                      "meta": {}
                    }
                    """
            ),
            step(
                "compact",
                SkillExecutionStepStatus.SKIPPED,
                "{\"skipped\":true}"
            )
        )
    );

    executor.execute(task);

    verifyNoInteractions(toolExecutionService);
    verify(coordinator).succeed(task, "{\"message\":\"cached\"}");
  }

  @Test
  void rendersPinnedPromptAndReadsPinnedResource() {
    when(coordinator.beginStep(any(), any(), any(), any()))
        .thenReturn(true);
    when(coordinator.completeStep(any(), any(), any()))
        .thenReturn(true);
    when(toolExecutionService.getWorkflowPrompt(any())).thenReturn(
        new McpGatewayPromptGetResult(
            "Greeting",
            List.of(Map.of(
                "role", "user",
                "content", Map.of("type", "text", "text", "Hello Ada")
            )),
            Map.of()
        )
    );
    when(toolExecutionService.readWorkflowResource(any())).thenReturn(
        new McpGatewayResourceReadResult(
            List.of(Map.of(
                "uri", "docs://guide/start",
                "mimeType", "text/plain",
                "text", "Resource body"
            )),
            Map.of()
        )
    );
    String workflow = """
        {
          "steps": [
            {
              "id": "render",
              "type": "prompt",
              "prompt": "welcome",
              "arguments": {"name": {"$ref": "input.name"}}
            },
            {
              "id": "read",
              "type": "resource",
              "resource": "guide",
              "uri": {"$ref": "input.uri"}
            }
          ],
          "output": {
            "prompt": {"$ref": "steps.render.messages"},
            "resource": {"$ref": "steps.read.contents"}
          }
        }
        """;
    ExecutionTask task = plannedTask(
        "{\"name\":\"Ada\",\"uri\":\"docs://guide/start\"}",
        workflow,
        """
            {
              "prompt": {"$ref": "steps.render.messages"},
              "resource": {"$ref": "steps.read.contents"}
            }
            """,
        List.of(
            promptStep("render"),
            resourceStep("read")
        )
    );

    executor.execute(task);

    ArgumentCaptor<McpWorkflowPromptGetRequest> promptRequest =
        ArgumentCaptor.forClass(McpWorkflowPromptGetRequest.class);
    verify(toolExecutionService).getWorkflowPrompt(promptRequest.capture());
    org.assertj.core.api.Assertions.assertThat(
        promptRequest.getValue().arguments()
    ).isEqualTo(Map.of("name", "Ada"));
    ArgumentCaptor<McpWorkflowResourceReadRequest> resourceRequest =
        ArgumentCaptor.forClass(McpWorkflowResourceReadRequest.class);
    verify(toolExecutionService).readWorkflowResource(
        resourceRequest.capture()
    );
    org.assertj.core.api.Assertions.assertThat(resourceRequest.getValue().uri())
        .isEqualTo("docs://guide/start");
    org.assertj.core.api.Assertions.assertThat(
        resourceRequest.getValue().resourceTemplate()
    ).isTrue();
    verify(coordinator).succeed(eq(task), contains("Resource body"));
  }

  @Test
  void executesParallelBranchesConcurrentlyAndJoinsTheirResults()
      throws InterruptedException {
    when(coordinator.checkpoint(any())).thenReturn(true);
    when(coordinator.beginParallelStep(any(), any(), any(), any()))
        .thenReturn(true);
    when(coordinator.completeParallelStep(any(), any(), any()))
        .thenReturn(true);
    CountDownLatch started = new CountDownLatch(2);
    AtomicBoolean overlapped = new AtomicBoolean();
    when(toolExecutionService.callWorkflowTool(any())).thenAnswer(invocation -> {
      started.countDown();
      if (started.await(2, TimeUnit.SECONDS)) {
        overlapped.set(true);
      }
      McpWorkflowToolCallRequest request = invocation.getArgument(0);
      return result(String.valueOf(request.arguments().get("message")));
    });
    String workflow = """
        {
          "steps": [
            {
              "id": "fanout",
              "type": "parallel",
              "branches": [
                {
                  "id": "left",
                  "steps": [
                    {
                      "id": "left-call",
                      "type": "tool",
                      "tool": "echo",
                      "arguments": {"message": "left"}
                    }
                  ]
                },
                {
                  "id": "right",
                  "steps": [
                    {
                      "id": "right-call",
                      "type": "tool",
                      "tool": "echo",
                      "arguments": {"message": "right"}
                    }
                  ]
                }
              ]
            }
          ],
          "output": {
            "left": {
              "$ref": "steps.left-call.structuredContent.message"
            },
            "right": {
              "$ref": "steps.right-call.structuredContent.message"
            }
          }
        }
        """;
    ExecutionTask task = plannedTask(
        "{}",
        workflow,
        """
            {
              "left": {
                "$ref": "steps.left-call.structuredContent.message"
              },
              "right": {
                "$ref": "steps.right-call.structuredContent.message"
              }
            }
            """,
        List.of(step("left-call"), step("right-call"))
    );

    executor.execute(task);

    org.assertj.core.api.Assertions.assertThat(overlapped.get()).isTrue();
    verify(toolExecutionService, times(2)).callWorkflowTool(any());
    verify(coordinator, times(2)).checkpoint(task);
    verify(coordinator).succeed(
        task,
        "{\"left\":\"left\",\"right\":\"right\"}"
    );
  }

  @Test
  void pausesOnlyAfterEveryInFlightParallelBranchIsCheckpointed() {
    when(coordinator.checkpoint(any())).thenReturn(true, false);
    when(coordinator.beginParallelStep(any(), any(), any(), any()))
        .thenReturn(true);
    when(coordinator.completeParallelStep(any(), any(), any()))
        .thenReturn(true);
    when(toolExecutionService.callWorkflowTool(any()))
        .thenReturn(result("ok"));
    String workflow = """
        {
          "steps": [
            {
              "id": "fanout",
              "type": "parallel",
              "branches": [
                {
                  "id": "left",
                  "steps": [
                    {"id": "left-call", "type": "tool", "tool": "echo"}
                  ]
                },
                {
                  "id": "right",
                  "steps": [
                    {"id": "right-call", "type": "tool", "tool": "echo"}
                  ]
                }
              ]
            }
          ]
        }
        """;
    ExecutionTask task = plannedTask(
        "{}",
        workflow,
        null,
        List.of(step("left-call"), step("right-call"))
    );

    executor.execute(task);

    verify(toolExecutionService, times(2)).callWorkflowTool(any());
    verify(coordinator, times(2)).completeParallelStep(
        eq(task),
        any(),
        any()
    );
    verify(coordinator, never()).succeed(any(), any());
  }

  private static ExecutionTask task() {
    return new ExecutionTask(
        "execution-a",
        "skill-a",
        "version-a",
        AiResourceScope.SYSTEM,
        null,
        "user-a",
        "{\"message\":\"hello\"}",
        null,
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
        1,
        300,
        1024L * 1024L,
        0,
        20,
        Instant.now().plusSeconds(300),
        "worker-a",
        1,
        List.of(new StepTask(
            "echo-step",
            0,
            SkillExecutionStepStatus.PENDING,
            "tool",
            "echo",
            "server-a",
            "snapshot-a",
            "echo",
            "a".repeat(64),
            false,
            null,
            null
        ))
    );
  }

  private static ExecutionTask plannedTask(
      final String inputJson,
      final String workflowPlanJson,
      final String outputTemplateJson,
      final List<StepTask> steps
  ) {
    return new ExecutionTask(
        "execution-a",
        "skill-a",
        "version-a",
        AiResourceScope.SYSTEM,
        null,
        "user-a",
        inputJson,
        workflowPlanJson,
        outputTemplateJson,
        "{\"type\":\"object\"}",
        steps.size(),
        300,
        1024L * 1024L,
        0,
        20,
        Instant.now().plusSeconds(300),
        "worker-a",
        1,
        steps
    );
  }

  private static StepTask step(final String stepId) {
    return step(stepId, SkillExecutionStepStatus.PENDING, null);
  }

  private static StepTask step(
      final String stepId,
      final SkillExecutionStepStatus status,
      final String outputJson
  ) {
    return new StepTask(
        stepId,
        0,
        status,
        "tool",
        "echo",
        "server-a",
        "snapshot-a",
        "echo",
        "a".repeat(64),
        false,
        null,
        outputJson
    );
  }

  private static StepTask promptStep(final String stepId) {
    return new StepTask(
        stepId,
        0,
        SkillExecutionStepStatus.PENDING,
        "prompt",
        "welcome",
        "server-a",
        "snapshot-a",
        "welcome",
        "b".repeat(64),
        false,
        "{\"name\":{\"$ref\":\"input.name\"}}",
        null
    );
  }

  private static StepTask resourceStep(final String stepId) {
    return new StepTask(
        stepId,
        1,
        SkillExecutionStepStatus.PENDING,
        "resource",
        "guide",
        "server-a",
        "snapshot-a",
        "docs://guide/{page}",
        "c".repeat(64),
        true,
        "{\"$ref\":\"input.uri\"}",
        null
    );
  }

  private static McpGatewayToolCallResult result(final String message) {
    return new McpGatewayToolCallResult(
        List.of(Map.of("type", "text", "text", message)),
        false,
        Map.of("message", message),
        Map.of()
    );
  }
}
