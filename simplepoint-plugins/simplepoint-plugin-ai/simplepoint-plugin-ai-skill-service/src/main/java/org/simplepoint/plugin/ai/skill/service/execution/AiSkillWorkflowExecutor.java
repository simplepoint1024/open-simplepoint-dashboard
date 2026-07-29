package org.simplepoint.plugin.ai.skill.service.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallResult;
import org.simplepoint.plugin.ai.mcp.api.model.McpWorkflowToolCallRequest;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpWorkflowToolExecutionService;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStepStatus;
import org.simplepoint.plugin.ai.skill.api.properties.SkillExecutionProperties;
import org.simplepoint.plugin.ai.skill.service.execution.AiSkillExecutionCoordinator.ExecutionTask;
import org.simplepoint.plugin.ai.skill.service.execution.AiSkillExecutionCoordinator.StepTask;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowTemplateResolver;
import org.springframework.stereotype.Service;

/**
 * Executes claimed declarative Tool steps outside database transactions.
 */
@Service
public class AiSkillWorkflowExecutor {

  private static final TypeReference<Map<String, Object>> MAP_TYPE =
      new TypeReference<>() {
      };

  private final AiSkillExecutionCoordinator coordinator;

  private final AiMcpWorkflowToolExecutionService toolExecutionService;

  private final SkillWorkflowTemplateResolver templateResolver;

  private final SkillJsonSchemaValidator schemaValidator;

  private final SkillExecutionProperties properties;

  private final ObjectMapper objectMapper;

  private final ObjectMapper canonicalMapper;

  /**
   * Creates the declarative workflow executor.
   */
  public AiSkillWorkflowExecutor(
      final AiSkillExecutionCoordinator coordinator,
      final AiMcpWorkflowToolExecutionService toolExecutionService,
      final SkillWorkflowTemplateResolver templateResolver,
      final SkillJsonSchemaValidator schemaValidator,
      final SkillExecutionProperties properties,
      final ObjectMapper objectMapper
  ) {
    this.coordinator = coordinator;
    this.toolExecutionService = toolExecutionService;
    this.templateResolver = templateResolver;
    this.schemaValidator = schemaValidator;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.canonicalMapper = objectMapper.copy()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  }

  /**
   * Runs one claimed execution and checkpoints every completed step.
   */
  public void execute(final ExecutionTask task) {
    String currentStepId = null;
    try {
      Map<String, Object> input = readMap(task.inputJson(), "Skill input");
      Map<String, Object> results = new LinkedHashMap<>();
      for (StepTask step : task.steps()) {
        currentStepId = step.stepId();
        if (step.status() == SkillExecutionStepStatus.SUCCEEDED) {
          results.put(
              step.stepId(),
              readMap(step.outputJson(), "Skill step output")
          );
          continue;
        }
        Map<String, Object> arguments = resolveArguments(step, input, results);
        String argumentsJson = writeJson(arguments);
        assertPayloadSize(argumentsJson, "Skill Tool arguments");
        coordinator.beginStep(task, step.stepId(), argumentsJson);
        McpGatewayToolCallResult result =
            toolExecutionService.callWorkflowTool(
                new McpWorkflowToolCallRequest(
                    task.scopeType(),
                    task.tenantId(),
                    step.serverId(),
                    step.snapshotId(),
                    step.toolName(),
                    step.inputSchemaHash(),
                    arguments,
                    task.executionId(),
                    step.stepId(),
                    task.requestedBy()
                )
            );
        Map<String, Object> envelope = resultEnvelope(result);
        String outputJson = writeJson(envelope);
        assertPayloadSize(outputJson, "Skill Tool result");
        if (result.error()) {
          coordinator.fail(
              task,
              step.stepId(),
              "SKILL_TOOL_REPORTED_ERROR",
              "MCP Tool reported an execution error"
          );
          return;
        }
        coordinator.completeStep(task, step.stepId(), outputJson);
        results.put(step.stepId(), envelope);
      }
      Object output = resolveOutput(task, results);
      schemaValidator.validate(
          readMap(task.outputSchemaJson(), "Skill output Schema"),
          output,
          "Skill output"
      );
      String outputJson = writeJson(output);
      assertPayloadSize(outputJson, "Skill workflow output");
      coordinator.succeed(task, outputJson);
    } catch (RuntimeException ex) {
      coordinator.fail(
          task,
          currentStepId,
          "SKILL_WORKFLOW_EXECUTION_FAILED",
          ex.getMessage()
      );
    }
  }

  private Map<String, Object> resolveArguments(
      final StepTask step,
      final Map<String, Object> input,
      final Map<String, Object> results
  ) {
    if (step.argumentsTemplateJson() == null) {
      return new LinkedHashMap<>(input);
    }
    Object resolved = templateResolver.resolve(
        readValue(step.argumentsTemplateJson(), "Workflow arguments template"),
        input,
        results
    );
    if (!(resolved instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException(
          "Resolved workflow Tool arguments must be an object"
      );
    }
    return objectMapper.convertValue(map, MAP_TYPE);
  }

  private Object resolveOutput(
      final ExecutionTask task,
      final Map<String, Object> results
  ) {
    if (task.outputTemplateJson() != null) {
      return templateResolver.resolve(
          readValue(task.outputTemplateJson(), "Workflow output template"),
          readMap(task.inputJson(), "Skill input"),
          results
      );
    }
    if (task.steps().isEmpty()) {
      return Map.of();
    }
    Object lastValue = results.get(
        task.steps().get(task.steps().size() - 1).stepId()
    );
    if (!(lastValue instanceof Map<?, ?> last)) {
      return lastValue;
    }
    Object structured = last.get("structuredContent");
    return structured instanceof Map<?, ?> ? structured : last;
  }

  private static Map<String, Object> resultEnvelope(
      final McpGatewayToolCallResult result
  ) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("content", result.content());
    envelope.put("error", result.error());
    envelope.put("structuredContent", result.structuredContent());
    envelope.put("meta", result.meta());
    return envelope;
  }

  private Map<String, Object> readMap(
      final String json,
      final String label
  ) {
    if (json == null) {
      throw new IllegalStateException(label + " is missing");
    }
    try {
      return objectMapper.readValue(json, MAP_TYPE);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(label + " is corrupted", ex);
    }
  }

  private Object readValue(final String json, final String label) {
    try {
      return objectMapper.readValue(json, Object.class);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(label + " is corrupted", ex);
    }
  }

  private String writeJson(final Object value) {
    try {
      return canonicalMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException(
          "Skill workflow payload is not valid JSON",
          ex
      );
    }
  }

  private void assertPayloadSize(final String json, final String label) {
    Integer configured = properties.getMaximumPayloadBytes();
    int maximum = configured == null ? 256 * 1024 : configured;
    if (maximum < 1024
        || json.getBytes(StandardCharsets.UTF_8).length > maximum) {
      throw new IllegalArgumentException(label + " is too large");
    }
  }
}
