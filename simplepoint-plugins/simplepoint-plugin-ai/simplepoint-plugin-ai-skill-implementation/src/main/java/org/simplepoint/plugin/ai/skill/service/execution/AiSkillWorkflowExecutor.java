package org.simplepoint.plugin.ai.skill.service.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayPromptGetResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayResourceReadResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallResult;
import org.simplepoint.plugin.ai.mcp.api.model.McpWorkflowPromptGetRequest;
import org.simplepoint.plugin.ai.mcp.api.model.McpWorkflowResourceReadRequest;
import org.simplepoint.plugin.ai.mcp.api.model.McpWorkflowToolCallRequest;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpWorkflowExecutionService;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.TestAssertion;
import org.simplepoint.plugin.ai.skill.api.model.SkillDebugMode;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStepStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillTestAssertionResult;
import org.simplepoint.plugin.ai.skill.api.properties.SkillExecutionProperties;
import org.simplepoint.plugin.ai.skill.service.execution.AiSkillExecutionCoordinator.ExecutionTask;
import org.simplepoint.plugin.ai.skill.service.execution.AiSkillExecutionCoordinator.StepTask;
import org.simplepoint.plugin.ai.skill.service.execution.SkillCapabilityTokenIssuer.IssuedCapability;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;
import org.simplepoint.plugin.ai.skill.service.support.SkillMockAssertionEvaluator;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowConditionEvaluator;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler.ConditionNode;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler.ExecutableNode;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler.ParallelBranch;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler.ParallelNode;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler.PromptNode;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler.ResourceNode;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler.ToolNode;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler.WorkflowBindings;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler.WorkflowNode;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowPlanCompiler.WorkflowPlan;
import org.simplepoint.plugin.ai.skill.service.support.SkillWorkflowTemplateResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Executes claimed declarative MCP steps outside database transactions.
 */
@Service
@ConditionalOnProperty(
    prefix = SkillExecutionProperties.PREFIX,
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class AiSkillWorkflowExecutor {

  private static final TypeReference<Map<String, Object>> MAP_TYPE =
      new TypeReference<>() {
      };

  private static final TypeReference<List<TestAssertion>> ASSERTIONS_TYPE =
      new TypeReference<>() {
      };

  private final AiSkillExecutionCoordinator coordinator;

  private final AiMcpWorkflowExecutionService workflowExecutionService;

  private final SkillWorkflowTemplateResolver templateResolver;

  private final SkillWorkflowConditionEvaluator conditionEvaluator;

  private final SkillWorkflowPlanCompiler workflowPlanCompiler;

  private final SkillJsonSchemaValidator schemaValidator;

  private final SkillExecutionProperties properties;

  private final SkillCapabilityTokenIssuer capabilityTokenIssuer;

  private final SkillMockAssertionEvaluator assertionEvaluator;

  private final ObjectMapper objectMapper;

  private final ObjectMapper canonicalMapper;

  /**
   * Creates the declarative workflow executor.
   */
  public AiSkillWorkflowExecutor(
      final AiSkillExecutionCoordinator coordinator,
      final AiMcpWorkflowExecutionService workflowExecutionService,
      final SkillWorkflowTemplateResolver templateResolver,
      final SkillWorkflowConditionEvaluator conditionEvaluator,
      final SkillWorkflowPlanCompiler workflowPlanCompiler,
      final SkillJsonSchemaValidator schemaValidator,
      final SkillExecutionProperties properties,
      final SkillCapabilityTokenIssuer capabilityTokenIssuer,
      final SkillMockAssertionEvaluator assertionEvaluator,
      final ObjectMapper objectMapper
  ) {
    this.coordinator = coordinator;
    this.workflowExecutionService = workflowExecutionService;
    this.templateResolver = templateResolver;
    this.conditionEvaluator = conditionEvaluator;
    this.workflowPlanCompiler = workflowPlanCompiler;
    this.schemaValidator = schemaValidator;
    this.properties = properties;
    this.capabilityTokenIssuer = capabilityTokenIssuer;
    this.assertionEvaluator = assertionEvaluator;
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
      Map<String, StepTask> steps = indexSteps(task);
      Map<String, Object> results = new LinkedHashMap<>();
      WorkflowPlan plan = workflowPlan(task, steps.keySet());
      for (WorkflowNode node : plan.nodes()) {
        currentStepId = node.id();
        if (!executeNode(task, node, steps, input, results)) {
          return;
        }
      }
      Object output = resolveOutput(task, results);
      schemaValidator.validate(
          readMap(task.outputSchemaJson(), "Skill output Schema"),
          output,
          "Skill output"
      );
      String outputJson = writeJson(output);
      assertPayloadSize(outputJson, "Skill workflow output");
      if (task.debugMode() == SkillDebugMode.MOCK) {
        List<SkillTestAssertionResult> assertionResults =
            assertionEvaluator.evaluate(mockAssertions(task), results, output);
        String assertionResultsJson = writeJson(assertionResults);
        assertPayloadSize(
            assertionResultsJson,
            "Skill MOCK assertion results"
        );
        coordinator.completeMock(
            task,
            outputJson,
            assertionResultsJson,
            assertionResults.stream().allMatch(
                SkillTestAssertionResult::passed
            )
        );
      } else {
        coordinator.succeed(task, outputJson);
      }
    } catch (WorkflowStepException ex) {
      coordinator.fail(
          task,
          ex.stepId(),
          ex.errorCode(),
          ex.getMessage()
      );
    } catch (SkillExecutionBudgetExceededException ex) {
      coordinator.fail(
          task,
          currentStepId,
          ex.errorCode(),
          ex.getMessage()
      );
    } catch (RuntimeException ex) {
      coordinator.fail(
          task,
          currentStepId,
          "SKILL_WORKFLOW_EXECUTION_FAILED",
          ex.getMessage()
      );
    }
  }

  private boolean executeNode(
      final ExecutionTask task,
      final WorkflowNode node,
      final Map<String, StepTask> steps,
      final Map<String, Object> input,
      final Map<String, Object> results
  ) {
    return switch (node) {
      case ExecutableNode executable -> executeSequentialStep(
          task,
          executable,
          requireStep(steps, executable.id()),
          input,
          results
      );
      case ConditionNode condition -> executeCondition(
          task,
          condition,
          steps,
          input,
          results
      );
      case ParallelNode parallel -> executeParallel(
          task,
          parallel,
          steps,
          input,
          results
      );
    };
  }

  private boolean executeCondition(
      final ExecutionTask task,
      final ConditionNode node,
      final Map<String, StepTask> steps,
      final Map<String, Object> input,
      final Map<String, Object> results
  ) {
    if (!coordinator.checkpoint(task)) {
      return false;
    }
    boolean matched = conditionEvaluator.evaluate(
        node.condition(),
        input,
        results
    );
    List<ExecutableNode> selected = matched
        ? node.whenTrue() : node.whenFalse();
    List<ExecutableNode> skipped = matched
        ? node.whenFalse() : node.whenTrue();
    if (!coordinator.skipSteps(
        task,
        skipped.stream().map(ExecutableNode::id).toList()
    )) {
      return false;
    }
    for (ExecutableNode step : skipped) {
      results.put(step.id(), Map.of("skipped", true));
    }
    for (ExecutableNode step : selected) {
      if (!executeSequentialStep(
          task,
          step,
          requireStep(steps, step.id()),
          input,
          results
      )) {
        return false;
      }
    }
    results.put(node.id(), Map.of(
        "matched", matched,
        "selectedBranch", matched ? "then" : "else"
    ));
    return true;
  }

  private boolean executeParallel(
      final ExecutionTask task,
      final ParallelNode node,
      final Map<String, StepTask> steps,
      final Map<String, Object> input,
      final Map<String, Object> results
  ) {
    if (!coordinator.checkpoint(task)) {
      return false;
    }
    Map<String, Object> baseResults = Map.copyOf(results);
    List<BranchResult> branchResults = new ArrayList<>();
    try (ExecutorService executor =
             Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<BranchResult>> futures = node.branches().stream()
          .map(branch -> executor.submit(() -> executeParallelBranch(
              task,
              branch,
              steps,
              input,
              baseResults
          )))
          .toList();
      for (Future<BranchResult> future : futures) {
        branchResults.add(future.get());
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new WorkflowStepException(
          node.id(),
          "SKILL_PARALLEL_INTERRUPTED",
          "Skill parallel workflow was interrupted",
          ex
      );
    } catch (ExecutionException ex) {
      throw new WorkflowStepException(
          node.id(),
          "SKILL_PARALLEL_EXECUTION_FAILED",
          "Skill parallel workflow failed",
          ex.getCause()
      );
    }
    List<BranchResult> failures = branchResults.stream()
        .filter(branch -> branch.failure() != null)
        .toList();
    if (!failures.isEmpty()) {
      BranchResult first = failures.getFirst();
      RuntimeException failure = first.failure();
      String errorCode = "SKILL_PARALLEL_BRANCH_FAILED";
      String message = failure.getMessage();
      if (failure instanceof WorkflowStepException workflowFailure) {
        errorCode = workflowFailure.errorCode();
      } else if (failure
          instanceof SkillExecutionBudgetExceededException budget) {
        errorCode = budget.errorCode();
      }
      List<String> failedStepIds = failures.stream()
          .map(BranchResult::failedStepId)
          .filter(value -> value != null && !value.isBlank())
          .distinct()
          .toList();
      coordinator.fail(
          task,
          failedStepIds,
          errorCode,
          message
      );
      return false;
    }
    branchResults.forEach(branch -> results.putAll(branch.results()));
    if (!coordinator.checkpoint(task)) {
      return false;
    }
    results.put(node.id(), Map.of(
        "branchCount", node.branches().size(),
        "status", "SUCCEEDED"
    ));
    return true;
  }

  private BranchResult executeParallelBranch(
      final ExecutionTask task,
      final ParallelBranch branch,
      final Map<String, StepTask> steps,
      final Map<String, Object> input,
      final Map<String, Object> baseResults
  ) {
    Map<String, Object> localResults = new LinkedHashMap<>(baseResults);
    Map<String, Object> branchResults = new LinkedHashMap<>();
    String currentStepId = null;
    try {
      for (ExecutableNode step : branch.steps()) {
        currentStepId = step.id();
        StepResult result = executeStep(
            task,
            step,
            requireStep(steps, step.id()),
            input,
            localResults,
            true
        );
        localResults.put(step.id(), result.output());
        branchResults.put(step.id(), result.output());
      }
      return new BranchResult(
          branch.id(),
          Map.copyOf(branchResults),
          null,
          null
      );
    } catch (RuntimeException ex) {
      return new BranchResult(
          branch.id(),
          Map.copyOf(branchResults),
          currentStepId,
          ex
      );
    }
  }

  private boolean executeSequentialStep(
      final ExecutionTask task,
      final ExecutableNode node,
      final StepTask step,
      final Map<String, Object> input,
      final Map<String, Object> results
  ) {
    StepResult result = executeStep(
        task,
        node,
        step,
        input,
        results,
        false
    );
    results.put(node.id(), result.output());
    return result.continuing();
  }

  private StepResult executeStep(
      final ExecutionTask task,
      final ExecutableNode node,
      final StepTask step,
      final Map<String, Object> input,
      final Map<String, Object> results,
      final boolean parallel
  ) {
    if (step.status() == SkillExecutionStepStatus.SUCCEEDED
        || step.status() == SkillExecutionStepStatus.SKIPPED) {
      return new StepResult(
          readMap(step.outputJson(), "Skill step output"),
          true
      );
    }
    ResolvedInvocation invocation = resolveInvocation(
        node,
        step,
        input,
        results
    );
    String inputJson = writeJson(invocation.input());
    assertPayloadSize(inputJson, "Skill MCP step input");
    IssuedCapability capability = task.debugMode() == SkillDebugMode.MOCK
        ? null : capabilityTokenIssuer.issue(task, step, invocation.target());
    String tokenIdHash = capability == null
        ? task.mockConfigHash() : capability.tokenIdHash();
    boolean started = parallel
        ? coordinator.beginParallelStep(
            task,
            step.stepId(),
            inputJson,
            tokenIdHash
        )
        : coordinator.beginStep(
            task,
            step.stepId(),
            inputJson,
            tokenIdHash
        );
    if (!started) {
      return new StepResult(Map.of(), false);
    }
    Map<String, Object> envelope;
    try {
      envelope = task.debugMode() == SkillDebugMode.MOCK
          ? invokeMock(task, node, step)
          : invoke(task, node, step, invocation, capability.token());
    } catch (WorkflowStepException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      throw new WorkflowStepException(
          step.stepId(),
          failureCode(step.stepType()),
          ex.getMessage(),
          ex
      );
    }
    String outputJson = writeJson(envelope);
    assertPayloadSize(outputJson, "Skill MCP step result");
    boolean continuing = parallel
        ? coordinator.completeParallelStep(
            task,
            step.stepId(),
            outputJson
        )
        : coordinator.completeStep(
            task,
            step.stepId(),
            outputJson
        );
    return new StepResult(envelope, continuing);
  }

  private Map<String, Object> invoke(
      final ExecutionTask task,
      final ExecutableNode node,
      final StepTask step,
      final ResolvedInvocation invocation,
      final String capabilityToken
  ) {
    return switch (node) {
      case ToolNode ignored -> {
        McpGatewayToolCallResult result =
            workflowExecutionService.callWorkflowTool(
                new McpWorkflowToolCallRequest(
                    task.scopeType(),
                    task.tenantId(),
                    step.serverId(),
                    step.snapshotId(),
                    step.capabilityName(),
                    step.capabilitySchemaHash(),
                    invocation.input(),
                    task.skillId(),
                    task.skillVersionId(),
                    task.executionId(),
                    step.stepId(),
                    task.requestedBy(),
                    capabilityToken
                )
            );
        if (result.error()) {
          throw new WorkflowStepException(
              step.stepId(),
              "SKILL_TOOL_REPORTED_ERROR",
              "MCP Tool reported an execution error",
              null
          );
        }
        yield toolEnvelope(result);
      }
      case PromptNode ignored -> promptEnvelope(
          workflowExecutionService.getWorkflowPrompt(
              new McpWorkflowPromptGetRequest(
                  task.scopeType(),
                  task.tenantId(),
                  step.serverId(),
                  step.snapshotId(),
                  step.capabilityName(),
                  step.capabilitySchemaHash(),
                  invocation.input(),
                  task.skillId(),
                  task.skillVersionId(),
                  task.executionId(),
                  step.stepId(),
                  task.requestedBy(),
                  capabilityToken
              )
          )
      );
      case ResourceNode ignored -> resourceEnvelope(
          workflowExecutionService.readWorkflowResource(
              new McpWorkflowResourceReadRequest(
                  task.scopeType(),
                  task.tenantId(),
                  step.serverId(),
                  step.snapshotId(),
                  invocation.target(),
                  step.capabilityName(),
                  step.capabilityTemplate(),
                  step.capabilitySchemaHash(),
                  task.skillId(),
                  task.skillVersionId(),
                  task.executionId(),
                  step.stepId(),
                  task.requestedBy(),
                  capabilityToken
              )
          )
      );
    };
  }

  private Map<String, Object> invokeMock(
      final ExecutionTask task,
      final ExecutableNode node,
      final StepTask step
  ) {
    Map<String, Object> config = readMap(
        task.mockConfigJson(),
        "Skill MOCK configuration"
    );
    Object source = config.get("mocks");
    if (!(source instanceof Map<?, ?> mocks)
        || !(mocks.get(step.stepId()) instanceof Map<?, ?> rawMock)) {
      throw new WorkflowStepException(
          step.stepId(),
          "SKILL_MOCK_RESULT_MISSING",
          "Pinned MOCK result does not exist for workflow step",
          null
      );
    }
    Map<String, Object> mock = objectMapper.convertValue(rawMock, MAP_TYPE);
    if ("ERROR".equals(mock.get("mode"))) {
      Object rawCode = mock.get("errorCode");
      Object rawMessage = mock.get("errorMessage");
      throw new WorkflowStepException(
          step.stepId(),
          rawCode instanceof String code && !code.isBlank()
              ? code : "SKILL_MOCK_REPORTED_ERROR",
          rawMessage instanceof String message && !message.isBlank()
              ? message : "Mocked workflow step failed",
          null
      );
    }
    if (!"SUCCESS".equals(mock.get("mode"))) {
      throw new WorkflowStepException(
          step.stepId(),
          "SKILL_MOCK_RESULT_INVALID",
          "Pinned MOCK result mode is invalid",
          null
      );
    }
    Object output = mock.get("output");
    Map<String, Object> meta = Map.of(
        "mock", true,
        "testCaseId", config.get("testCaseId")
    );
    return switch (node) {
      case ToolNode ignored -> {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("content", List.of());
        envelope.put("error", false);
        envelope.put("structuredContent", output);
        envelope.put("meta", meta);
        yield envelope;
      }
      case PromptNode ignored -> Map.of(
          "description", "Mocked MCP Prompt result",
          "messages", output == null ? List.of() : output,
          "meta", meta
      );
      case ResourceNode ignored -> Map.of(
          "contents", output == null ? List.of() : output,
          "meta", meta
      );
    };
  }

  private List<TestAssertion> mockAssertions(final ExecutionTask task) {
    Map<String, Object> config = readMap(
        task.mockConfigJson(),
        "Skill MOCK configuration"
    );
    Object assertions = config.get("assertions");
    if (assertions == null) {
      return List.of();
    }
    return objectMapper.convertValue(assertions, ASSERTIONS_TYPE);
  }

  private ResolvedInvocation resolveInvocation(
      final ExecutableNode node,
      final StepTask step,
      final Map<String, Object> input,
      final Map<String, Object> results
  ) {
    return switch (node) {
      case ToolNode tool -> {
        Map<String, Object> arguments = resolveArguments(
            tool.argumentsTemplate(),
            input,
            results,
            true,
            "Tool"
        );
        yield new ResolvedInvocation(step.capabilityName(), arguments);
      }
      case PromptNode prompt -> {
        Map<String, Object> arguments = resolveArguments(
            prompt.argumentsTemplate(),
            input,
            results,
            false,
            "Prompt"
        );
        yield new ResolvedInvocation(step.capabilityName(), arguments);
      }
      case ResourceNode resource -> {
        String uri = resolveResourceUri(
            resource.uriTemplate(),
            step,
            input,
            results
        );
        yield new ResolvedInvocation(uri, Map.of("uri", uri));
      }
    };
  }

  private Map<String, Object> resolveArguments(
      final Object template,
      final Map<String, Object> input,
      final Map<String, Object> results,
      final boolean defaultToInput,
      final String type
  ) {
    if (template == null) {
      return defaultToInput
          ? new LinkedHashMap<>(input) : new LinkedHashMap<>();
    }
    Object resolved = templateResolver.resolve(template, input, results);
    if (!(resolved instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException(
          "Resolved workflow " + type + " arguments must be an object"
      );
    }
    return objectMapper.convertValue(map, MAP_TYPE);
  }

  private String resolveResourceUri(
      final Object template,
      final StepTask step,
      final Map<String, Object> input,
      final Map<String, Object> results
  ) {
    Object resolved = template == null
        ? step.capabilityName()
        : templateResolver.resolve(template, input, results);
    if (!(resolved instanceof String uri)
        || uri.isBlank()
        || uri.length() > 4096
        || uri.contains("\r")
        || uri.contains("\n")) {
      throw new IllegalArgumentException(
          "Resolved workflow Resource URI is invalid"
      );
    }
    try {
      URI.create(uri);
      return uri;
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException(
          "Resolved workflow Resource URI is invalid",
          ex
      );
    }
  }

  private WorkflowPlan workflowPlan(
      final ExecutionTask task,
      final Set<String> stepIds
  ) {
    if (task.workflowPlanJson() == null) {
      List<WorkflowNode> nodes = task.steps().stream()
          .map(step -> (WorkflowNode) new ToolNode(
              step.stepId(),
              step.capabilityAlias() == null
                  ? "legacy" : step.capabilityAlias(),
              step.inputTemplateJson() == null
                  ? null : readValue(
                      step.inputTemplateJson(),
                      "Workflow input template"
                  )
          ))
          .toList();
      List<ExecutableNode> executable = nodes.stream()
          .map(ExecutableNode.class::cast)
          .toList();
      return new WorkflowPlan(
          nodes,
          null,
          nodes.size(),
          executable
      );
    }
    Map<String, Object> workflow = readMap(
        task.workflowPlanJson(),
        "Skill workflow plan"
    );
    WorkflowBindings bindings = new WorkflowBindings(
        aliases(task, "tool"),
        aliases(task, "prompt"),
        aliases(task, "resource")
    );
    WorkflowPlan plan = workflowPlanCompiler.compile(workflow, bindings);
    Set<String> plannedSteps = plan.executableSteps().stream()
        .map(ExecutableNode::id)
        .collect(Collectors.toSet());
    if (!plannedSteps.equals(stepIds)) {
      throw new IllegalStateException(
          "Skill workflow plan does not match persisted MCP steps"
      );
    }
    return plan;
  }

  private static Set<String> aliases(
      final ExecutionTask task,
      final String stepType
  ) {
    return task.steps().stream()
        .filter(step -> stepType.equals(step.stepType()))
        .map(StepTask::capabilityAlias)
        .filter(value -> value != null && !value.isBlank())
        .collect(Collectors.toSet());
  }

  private static Map<String, StepTask> indexSteps(
      final ExecutionTask task
  ) {
    Map<String, StepTask> result = new HashMap<>();
    for (StepTask step : task.steps()) {
      if (result.put(step.stepId(), step) != null) {
        throw new IllegalStateException(
            "Skill execution contains duplicate workflow step IDs"
        );
      }
    }
    return Map.copyOf(result);
  }

  private static StepTask requireStep(
      final Map<String, StepTask> steps,
      final String stepId
  ) {
    StepTask step = steps.get(stepId);
    if (step == null) {
      throw new IllegalStateException(
          "Skill workflow step does not exist: " + stepId
      );
    }
    return step;
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

  private static Map<String, Object> toolEnvelope(
      final McpGatewayToolCallResult result
  ) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("content", result.content());
    envelope.put("error", result.error());
    envelope.put("structuredContent", result.structuredContent());
    envelope.put("meta", result.meta());
    return envelope;
  }

  private static Map<String, Object> promptEnvelope(
      final McpGatewayPromptGetResult result
  ) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("description", result.description());
    envelope.put("messages", result.messages());
    envelope.put("meta", result.meta());
    return envelope;
  }

  private static Map<String, Object> resourceEnvelope(
      final McpGatewayResourceReadResult result
  ) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("contents", result.contents());
    envelope.put("meta", result.meta());
    return envelope;
  }

  private static String failureCode(final String stepType) {
    return switch (stepType) {
      case "tool" -> "SKILL_TOOL_CALL_FAILED";
      case "prompt" -> "SKILL_PROMPT_GET_FAILED";
      case "resource" -> "SKILL_RESOURCE_READ_FAILED";
      default -> "SKILL_MCP_CAPABILITY_FAILED";
    };
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

  private record ResolvedInvocation(
      String target,
      Map<String, Object> input
  ) {
  }

  private record StepResult(
      Map<String, Object> output,
      boolean continuing
  ) {
  }

  private record BranchResult(
      String branchId,
      Map<String, Object> results,
      String failedStepId,
      RuntimeException failure
  ) {
  }

  private static final class WorkflowStepException
      extends RuntimeException {

    private final String stepId;

    private final String errorCode;

    private WorkflowStepException(
        final String stepId,
        final String errorCode,
        final String message,
        final Throwable cause
    ) {
      super(message, cause);
      this.stepId = stepId;
      this.errorCode = errorCode;
    }

    private String stepId() {
      return stepId;
    }

    private String errorCode() {
      return errorCode;
    }
  }
}
