package org.simplepoint.plugin.ai.workflow.service.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecution;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentWorkflowExecutionCommand;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentExecutionRepository;
import org.simplepoint.plugin.ai.core.service.support.AiExecutionDiagnostics;
import org.simplepoint.plugin.ai.core.service.support.AiExecutionDiagnostics.StableDiagnostic;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecution;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillWorkflowExecutionCommand;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillExecutionRepository;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowExecution;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowHumanTask;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowNodeExecution;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionEventType;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionStatus;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowHumanTaskStatus;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowNodeExecutionStatus;
import org.simplepoint.plugin.ai.workflow.api.properties.WorkflowExecutionProperties;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowExecutionRepository;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowHumanTaskRepository;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowNodeExecutionRepository;
import org.simplepoint.plugin.ai.workflow.service.execution.AiWorkflowExecutionCoordinator.ExecutionTask;
import org.simplepoint.plugin.ai.workflow.service.execution.AiWorkflowExecutionCoordinator.StaleWorkflowLeaseException;
import org.simplepoint.plugin.ai.workflow.service.support.WorkflowConditionEvaluator;
import org.simplepoint.plugin.ai.workflow.service.support.WorkflowValueResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Advances one durable Workflow through safe, fenced checkpoints.
 */
@Service
public class AiWorkflowExecutionEngine {

  private static final TypeReference<Map<String, Object>> MAP_TYPE =
      new TypeReference<>() {
      };

  private static final Set<WorkflowNodeExecutionStatus> NODE_TERMINAL =
      Set.of(
          WorkflowNodeExecutionStatus.SUCCEEDED,
          WorkflowNodeExecutionStatus.FAILED,
          WorkflowNodeExecutionStatus.CANCELLED,
          WorkflowNodeExecutionStatus.SKIPPED,
          WorkflowNodeExecutionStatus.COMPENSATED,
          WorkflowNodeExecutionStatus.COMPENSATION_FAILED
      );

  private final AiWorkflowExecutionRepository executionRepository;

  private final AiWorkflowNodeExecutionRepository nodeRepository;

  private final AiWorkflowHumanTaskRepository taskRepository;

  private final AiAgentExecutionRepository agentExecutionRepository;

  private final AiSkillExecutionRepository skillExecutionRepository;

  private final AiWorkflowChildLaunchCoordinator childLaunchCoordinator;

  private final AiWorkflowTerminationCoordinator terminationCoordinator;

  private final AiWorkflowExecutionEventPublisher eventPublisher;

  private final WorkflowValueResolver valueResolver;

  private final WorkflowConditionEvaluator conditionEvaluator;

  private final SkillJsonSchemaValidator schemaValidator;

  private final WorkflowExecutionProperties properties;

  private final ObjectMapper objectMapper;

  /**
   * Creates the durable Workflow execution engine.
   */
  public AiWorkflowExecutionEngine(
      final AiWorkflowExecutionRepository executionRepository,
      final AiWorkflowNodeExecutionRepository nodeRepository,
      final AiWorkflowHumanTaskRepository taskRepository,
      final AiAgentExecutionRepository agentExecutionRepository,
      final AiSkillExecutionRepository skillExecutionRepository,
      final AiWorkflowChildLaunchCoordinator childLaunchCoordinator,
      final AiWorkflowTerminationCoordinator terminationCoordinator,
      final AiWorkflowExecutionEventPublisher eventPublisher,
      final WorkflowValueResolver valueResolver,
      final WorkflowConditionEvaluator conditionEvaluator,
      final SkillJsonSchemaValidator schemaValidator,
      final WorkflowExecutionProperties properties,
      final ObjectMapper objectMapper
  ) {
    this.executionRepository = executionRepository;
    this.nodeRepository = nodeRepository;
    this.taskRepository = taskRepository;
    this.agentExecutionRepository = agentExecutionRepository;
    this.skillExecutionRepository = skillExecutionRepository;
    this.childLaunchCoordinator = childLaunchCoordinator;
    this.terminationCoordinator = terminationCoordinator;
    this.eventPublisher = eventPublisher;
    this.valueResolver = valueResolver;
    this.conditionEvaluator = conditionEvaluator;
    this.schemaValidator = schemaValidator;
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  /**
   * Advances an exact claimed worker generation by one durable scheduling turn.
   */
  @Transactional(rollbackFor = Exception.class)
  public void advance(final ExecutionTask task) {
    AiWorkflowExecution execution = requireOwned(task);
    sanitizeExecutionDiagnostic(execution);
    Plan plan = plan(execution);
    List<AiWorkflowNodeExecution> checkpoints =
        nodeRepository.findAllActiveByExecutionId(execution.getId());
    checkpoints.forEach(AiWorkflowExecutionEngine::sanitizeNodeDiagnostic);
    Map<String, AiWorkflowNodeExecution> nodes = new LinkedHashMap<>();
    checkpoints.forEach(node -> nodes.put(node.getNodeId(), node));
    Map<String, Object> input = readMap(
        execution.getInputJson(),
        "Workflow execution input"
    );
    Map<String, Object> outputs = outputs(checkpoints);
    Instant now = Instant.now();

    refreshWaiting(
        execution,
        plan,
        nodes,
        input,
        outputs,
        now
    );
    if (terminal(execution.getStatus())) {
      executionRepository.save(execution);
      return;
    }
    if (Boolean.TRUE.equals(execution.getPauseRequested())) {
      pause(execution, now);
      executionRepository.save(execution);
      return;
    }
    if (execution.getStatus() == WorkflowExecutionStatus.COMPENSATING) {
      advanceCompensation(execution, plan, nodes, input, outputs, now);
      executionRepository.save(execution);
      return;
    }
    if (!now.isBefore(execution.getDeadlineAt())) {
      failExecution(
          execution,
          plan,
          nodes,
          "WORKFLOW_BUDGET_TIME_EXCEEDED",
          "Workflow duration budget was exhausted",
          now
      );
      executionRepository.save(execution);
      return;
    }

    boolean failFast = "FAIL_FAST".equals(plan.failureMode());
    Optional<AiWorkflowNodeExecution> failed = nodes.values().stream()
        .filter(node ->
            node.getStatus() == WorkflowNodeExecutionStatus.FAILED)
        .findFirst();
    Optional<AiWorkflowNodeExecution> budgetFailure = nodes.values().stream()
        .filter(node ->
            node.getStatus() == WorkflowNodeExecutionStatus.FAILED)
        .filter(node ->
            "WORKFLOW_BUDGET_NODE_EXECUTIONS_EXCEEDED".equals(
                node.getErrorCode()
            ))
        .findFirst();
    if ((failFast && failed.isPresent()) || budgetFailure.isPresent()) {
      AiWorkflowNodeExecution node = budgetFailure.orElseGet(
          failed::orElseThrow
      );
      failExecution(
          execution,
          plan,
          nodes,
          node.getErrorCode(),
          node.getErrorMessage(),
          now
      );
      executionRepository.save(execution);
      return;
    }

    int safety = 0;
    boolean progressed;
    do {
      progressed = false;
      int active = activeNodes(nodes.values());
      for (NodeDefinition definition : plan.nodes()) {
        AiWorkflowNodeExecution node = nodes.get(definition.id());
        if (node.getStatus() != WorkflowNodeExecutionStatus.PENDING) {
          continue;
        }
        Eligibility eligibility = eligibility(
            definition,
            plan.edges(),
            nodes,
            input,
            outputs
        );
        if (eligibility == Eligibility.BLOCKED) {
          continue;
        }
        if (eligibility == Eligibility.SKIP) {
          skipNode(execution, node, now);
          outputs.put(node.getNodeId(), Map.of("skipped", true));
          progressed = true;
          continue;
        }
        if (active >= execution.getMaximumParallelism()) {
          continue;
        }
        executeNode(
            execution,
            plan,
            definition,
            node,
            nodes,
            input,
            outputs,
            now
        );
        progressed = true;
        if (node.getStatus() == WorkflowNodeExecutionStatus.WAITING) {
          active++;
        }
        if (node.getStatus() == WorkflowNodeExecutionStatus.FAILED
            && (failFast
                || "WORKFLOW_BUDGET_NODE_EXECUTIONS_EXCEEDED".equals(
                    node.getErrorCode()
                ))) {
          failExecution(
              execution,
              plan,
              nodes,
              node.getErrorCode(),
              node.getErrorMessage(),
              Instant.now()
          );
          executionRepository.save(execution);
          return;
        }
      }
      safety++;
    } while (progressed && safety <= plan.nodes().size() + 1);

    if (nodes.values().stream().allMatch(node ->
        NODE_TERMINAL.contains(node.getStatus()))) {
      Optional<AiWorkflowNodeExecution> finalFailure =
          nodes.values().stream()
              .filter(node ->
                  node.getStatus() == WorkflowNodeExecutionStatus.FAILED)
              .findFirst();
      if (finalFailure.isPresent()) {
        AiWorkflowNodeExecution node = finalFailure.orElseThrow();
        failExecution(
            execution,
            plan,
            nodes,
            node.getErrorCode(),
            node.getErrorMessage(),
            Instant.now()
        );
      } else {
        completeExecution(execution, plan, input, outputs, Instant.now());
      }
    } else {
      park(execution, nodes.values(), Instant.now());
    }
    executionRepository.save(execution);
  }

  private void refreshWaiting(
      final AiWorkflowExecution execution,
      final Plan plan,
      final Map<String, AiWorkflowNodeExecution> nodes,
      final Map<String, Object> input,
      final Map<String, Object> outputs,
      final Instant now
  ) {
    for (AiWorkflowNodeExecution node : nodes.values()) {
      if (node.getStatus() == WorkflowNodeExecutionStatus.WAITING) {
        refreshNormalWait(
            execution,
            plan,
            node,
            nodes,
            input,
            outputs,
            now
        );
      } else if (node.getStatus()
          == WorkflowNodeExecutionStatus.COMPENSATING) {
        refreshCompensation(execution, node, now);
      }
      if (terminal(execution.getStatus())) {
        break;
      }
      if (node.getOutputJson() != null
          && (node.getStatus() == WorkflowNodeExecutionStatus.SUCCEEDED
              || node.getStatus()
                  == WorkflowNodeExecutionStatus.COMPENSATED)) {
        outputs.put(node.getNodeId(), readObject(
            node.getOutputJson(),
            "Workflow node output"
        ));
      }
    }
  }

  private void refreshNormalWait(
      final AiWorkflowExecution execution,
      final Plan plan,
      final AiWorkflowNodeExecution node,
      final Map<String, AiWorkflowNodeExecution> nodes,
      final Map<String, Object> input,
      final Map<String, Object> outputs,
      final Instant now
  ) {
    switch (node.getNodeType()) {
      case "agent" -> refreshAgent(execution, node, now);
      case "skill" -> refreshSkill(execution, node, now);
      case "human" -> refreshHuman(execution, node, nodes, now);
      case "wait" -> {
        if (node.getScheduledAt() != null
            && !now.isBefore(node.getScheduledAt())) {
          succeedNode(
              execution,
              node,
              Map.of("waited", true),
              now
          );
        }
      }
      default -> failNode(
          execution,
          node,
          "WORKFLOW_WAIT_STATE_INVALID",
          "Workflow node has an invalid waiting state",
          now
      );
    }
  }

  private void refreshAgent(
      final AiWorkflowExecution execution,
      final AiWorkflowNodeExecution node,
      final Instant now
  ) {
    AiAgentExecution child = agentExecutionRepository.findActiveById(
        node.getChildExecutionId()
    ).orElseThrow(() -> new IllegalStateException(
        "Workflow Agent child execution disappeared"
    ));
    if (child.getStatus() == AgentExecutionStatus.SUCCEEDED) {
      succeedNode(
          execution,
          node,
          readObject(child.getOutputJson(), "Agent child output"),
          now
      );
    } else if (Set.of(
        AgentExecutionStatus.FAILED,
        AgentExecutionStatus.REJECTED,
        AgentExecutionStatus.CANCELLED
    ).contains(child.getStatus())) {
      failNode(
          execution,
          node,
          "WORKFLOW_AGENT_CHILD_" + child.getStatus().name(),
          "WORKFLOW_AGENT_CHILD_" + child.getStatus().name(),
          now
      );
    }
  }

  private void refreshSkill(
      final AiWorkflowExecution execution,
      final AiWorkflowNodeExecution node,
      final Instant now
  ) {
    AiSkillExecution child = skillExecutionRepository.findActiveById(
        node.getChildExecutionId()
    ).orElseThrow(() -> new IllegalStateException(
        "Workflow Skill child execution disappeared"
    ));
    if (child.getStatus() == SkillExecutionStatus.SUCCEEDED) {
      succeedNode(
          execution,
          node,
          readObject(child.getOutputJson(), "Skill child output"),
          now
      );
    } else if (Set.of(
        SkillExecutionStatus.FAILED,
        SkillExecutionStatus.REJECTED,
        SkillExecutionStatus.CANCELLED
    ).contains(child.getStatus())) {
      failNode(
          execution,
          node,
          "WORKFLOW_SKILL_CHILD_" + child.getStatus().name(),
          "WORKFLOW_SKILL_CHILD_" + child.getStatus().name(),
          now
      );
    }
  }

  private void refreshHuman(
      final AiWorkflowExecution execution,
      final AiWorkflowNodeExecution node,
      final Map<String, AiWorkflowNodeExecution> nodes,
      final Instant now
  ) {
    AiWorkflowHumanTask task = taskRepository
        .findActiveByNodeExecutionId(node.getId())
        .orElseThrow(() -> new IllegalStateException(
            "Workflow human task disappeared"
        ));
    if (task.getStatus() == WorkflowHumanTaskStatus.COMPLETED) {
      succeedNode(
          execution,
          node,
          readObject(task.getOutputJson(), "Human task output"),
          now
      );
      return;
    }
    if (task.getStatus() != WorkflowHumanTaskStatus.OPEN
        || now.isBefore(task.getDueAt())) {
      return;
    }
    task.setStatus(WorkflowHumanTaskStatus.TIMED_OUT);
    task.setResolvedAt(now);
    task.setResolvedBy("workflow-runtime");
    taskRepository.save(task);
    eventPublisher.publish(
        execution,
        WorkflowExecutionEventType.HUMAN_TASK_TIMED_OUT,
        node.getId(),
        task.getId(),
        null,
        Map.of("nodeId", node.getNodeId()),
        now
    );
    switch (task.getTimeoutAction()) {
      case "CONTINUE" -> succeedNode(
          execution,
          node,
          Map.of("timedOut", true),
          now
      );
      case "CANCEL" -> {
        execution.setStatus(WorkflowExecutionStatus.CANCELLED);
        execution.setCompletedAt(now);
        execution.setInactiveSince(null);
        clearLease(execution);
        terminationCoordinator.terminate(
            execution,
            nodes.values(),
            "workflow-runtime",
            "WORKFLOW_HUMAN_TASK_TIMEOUT",
            now
        );
        eventPublisher.publish(
            execution,
            WorkflowExecutionEventType.EXECUTION_CANCELLED,
            node.getId(),
            task.getId(),
            "workflow-runtime",
            Map.of("reason", "HUMAN_TASK_TIMEOUT"),
            now
        );
      }
      default -> failNode(
          execution,
          node,
          "WORKFLOW_HUMAN_TASK_TIMEOUT",
          "Workflow human task timed out",
          now
      );
    }
  }

  private void executeNode(
      final AiWorkflowExecution execution,
      final Plan plan,
      final NodeDefinition definition,
      final AiWorkflowNodeExecution node,
      final Map<String, AiWorkflowNodeExecution> nodes,
      final Map<String, Object> input,
      final Map<String, Object> outputs,
      final Instant now
  ) {
    if (execution.getConsumedNodeExecutions()
        >= execution.getMaximumNodeExecutions()) {
      failNode(
          execution,
          node,
          "WORKFLOW_BUDGET_NODE_EXECUTIONS_EXCEEDED",
          "Workflow node execution budget was exhausted",
          now
      );
      return;
    }
    reserveNodeExecution(execution);
    Object resolvedInput = definition.source().containsKey("input")
        ? valueResolver.resolve(
            definition.source().get("input"),
            input,
            outputs
        )
        : defaultInput(definition.id(), plan.edges(), nodes, input, outputs);
    String inputJson = write(resolvedInput);
    assertPayloadSize(inputJson, "Workflow node input");
    try {
      if ("agent".equals(definition.type())) {
        launchAgent(
            execution,
            plan,
            definition,
            node,
            requireObject(resolvedInput, "Agent node input"),
            inputJson,
            now
        );
        return;
      }
      if ("skill".equals(definition.type())) {
        launchSkill(
            execution,
            plan,
            definition,
            node,
            requireObject(resolvedInput, "Skill node input"),
            inputJson,
            now
        );
        return;
      }
      startLocalNode(execution, node, inputJson, now);
      switch (definition.type()) {
        case "human" -> createHumanTask(
            execution,
            definition,
            node,
            resolvedInput,
            now
        );
        case "wait" -> waitNode(execution, definition, node, now);
        case "condition" -> succeedNode(
            execution,
            node,
            Map.of(
                "result",
                conditionEvaluator.evaluate(
                    definition.source().get("condition"),
                    input,
                    outputs
                )
            ),
            now
        );
        case "parallel" -> succeedNode(
            execution,
            node,
            Map.of("forked", true),
            now
        );
        case "end" -> succeedNode(
            execution,
            node,
            resolvedInput,
            now
        );
        default -> throw new IllegalArgumentException(
            "Unsupported Workflow node type: " + definition.type()
        );
      }
    } catch (RuntimeException ex) {
      if (isDurablyWaitingChild(node)) {
        throw ex;
      }
      if (("agent".equals(definition.type())
          || "skill".equals(definition.type()))
          && node.getStatus() == WorkflowNodeExecutionStatus.PENDING) {
        recordFailedChildAttempt(node, inputJson, now);
      }
      failNode(
          execution,
          node,
          "WORKFLOW_NODE_EXECUTION_FAILED",
          "WORKFLOW_NODE_EXECUTION_FAILED",
          Instant.now()
      );
    }
    if (node.getOutputJson() != null
        && node.getStatus() == WorkflowNodeExecutionStatus.SUCCEEDED) {
      outputs.put(node.getNodeId(), readObject(
          node.getOutputJson(),
          "Workflow node output"
      ));
    }
  }

  private static boolean isDurablyWaitingChild(
      final AiWorkflowNodeExecution node
  ) {
    return node.getStatus() == WorkflowNodeExecutionStatus.WAITING
        && node.getChildExecutionId() != null;
  }

  private static void recordFailedChildAttempt(
      final AiWorkflowNodeExecution node,
      final String inputJson,
      final Instant now
  ) {
    node.setInputJson(inputJson);
    node.setAttemptCount(
        (node.getAttemptCount() == null ? 0 : node.getAttemptCount()) + 1
    );
    node.setStartedAt(now);
  }

  private void startLocalNode(
      final AiWorkflowExecution execution,
      final AiWorkflowNodeExecution node,
      final String inputJson,
      final Instant now
  ) {
    node.setInputJson(inputJson);
    node.setStatus(WorkflowNodeExecutionStatus.RUNNING);
    node.setAttemptCount(node.getAttemptCount() + 1);
    node.setStartedAt(now);
    nodeRepository.save(node);
    startedEvent(execution, node, now);
  }

  private void startedEvent(
      final AiWorkflowExecution execution,
      final AiWorkflowNodeExecution node,
      final Instant now
  ) {
    eventPublisher.publish(
        execution,
        WorkflowExecutionEventType.NODE_STARTED,
        node.getId(),
        null,
        null,
        Map.of("nodeId", node.getNodeId(), "nodeType", node.getNodeType()),
        now
    );
  }

  private void launchAgent(
      final AiWorkflowExecution execution,
      final Plan plan,
      final NodeDefinition definition,
      final AiWorkflowNodeExecution node,
      final Map<String, Object> input,
      final String inputJson,
      final Instant now
  ) {
    Dependency dependency = plan.dependency(
        definition.id(),
        "AGENT"
    );
    AiWorkflowChildLaunchCoordinator.NodeLaunchResult result =
        childLaunchCoordinator.launchAgent(
            execution.getId(),
            definition.id(),
            inputJson,
            now,
            new AgentWorkflowExecutionCommand(
                dependency.resourceId(),
                dependency.resourceVersionId(),
                dependency.contentHash(),
                execution.getScopeType(),
                execution.getTenantId(),
                execution.getRequestedBy(),
                execution.getRequestContextId(),
                execution.getId() + ":node:" + definition.id(),
                input
            )
        );
    synchronizeNormalLaunch(node, result.node());
    startedEvent(execution, node, now);
    waitingEvent(execution, node, node.getChildExecutionId(), now);
  }

  private void launchSkill(
      final AiWorkflowExecution execution,
      final Plan plan,
      final NodeDefinition definition,
      final AiWorkflowNodeExecution node,
      final Map<String, Object> input,
      final String inputJson,
      final Instant now
  ) {
    Dependency dependency = plan.dependency(
        definition.id(),
        "SKILL"
    );
    AiWorkflowChildLaunchCoordinator.NodeLaunchResult result =
        childLaunchCoordinator.launchSkill(
            execution.getId(),
            definition.id(),
            inputJson,
            now,
            new SkillWorkflowExecutionCommand(
                dependency.resourceId(),
                dependency.resourceVersionId(),
                dependency.contentHash(),
                execution.getScopeType(),
                execution.getTenantId(),
                execution.getRequestedBy(),
                execution.getId() + ":node:" + definition.id(),
                input
            )
        );
    synchronizeNormalLaunch(node, result.node());
    startedEvent(execution, node, now);
    waitingEvent(execution, node, node.getChildExecutionId(), now);
  }

  private static void synchronizeNormalLaunch(
      final AiWorkflowNodeExecution target,
      final AiWorkflowNodeExecution persisted
  ) {
    target.setInputJson(persisted.getInputJson());
    target.setStatus(persisted.getStatus());
    target.setAttemptCount(persisted.getAttemptCount());
    target.setStartedAt(persisted.getStartedAt());
    target.setCompletedAt(persisted.getCompletedAt());
    target.setScheduledAt(persisted.getScheduledAt());
    target.setErrorCode(persisted.getErrorCode());
    target.setErrorMessage(persisted.getErrorMessage());
    sanitizeNodeDiagnostic(target);
    target.setChildType(persisted.getChildType());
    target.setChildResourceId(persisted.getChildResourceId());
    target.setChildResourceVersionId(
        persisted.getChildResourceVersionId()
    );
    target.setChildExecutionId(persisted.getChildExecutionId());
  }

  private void createHumanTask(
      final AiWorkflowExecution execution,
      final NodeDefinition definition,
      final AiWorkflowNodeExecution node,
      final Object context,
      final Instant now
  ) {
    Map<String, Object> schema = definition.source().get("inputSchema")
        instanceof Map<?, ?> raw
        ? castMap(raw)
        : Map.of("type", "object", "additionalProperties", true);
    schemaValidator.validateSchema(schema, "Human task response schema");
    int timeout = integer(
        definition.source().get("timeoutSeconds"),
        3600
    );
    AiWorkflowHumanTask task = new AiWorkflowHumanTask();
    task.setExecutionId(execution.getId());
    task.setNodeExecutionId(node.getId());
    task.setNodeId(node.getNodeId());
    task.setScopeType(execution.getScopeType());
    task.setTenantId(execution.getTenantId());
    task.setTitle(String.valueOf(definition.source().get("title")));
    task.setDescription(text(definition.source().get("description")));
    task.setInputSchemaJson(write(schema));
    task.setContextJson(write(context));
    task.setStatus(WorkflowHumanTaskStatus.OPEN);
    task.setTimeoutAction(String.valueOf(
        definition.source().getOrDefault("timeoutAction", "FAIL")
    ).toUpperCase(Locale.ROOT));
    task.setDueAt(now.plusSeconds(timeout));
    task = taskRepository.save(task);
    node.setStatus(WorkflowNodeExecutionStatus.WAITING);
    node.setScheduledAt(task.getDueAt());
    nodeRepository.save(node);
    eventPublisher.publish(
        execution,
        WorkflowExecutionEventType.HUMAN_TASK_CREATED,
        node.getId(),
        task.getId(),
        null,
        Map.of("nodeId", node.getNodeId(), "dueAt", task.getDueAt()),
        now
    );
  }

  private void waitNode(
      final AiWorkflowExecution execution,
      final NodeDefinition definition,
      final AiWorkflowNodeExecution node,
      final Instant now
  ) {
    node.setStatus(WorkflowNodeExecutionStatus.WAITING);
    node.setScheduledAt(now.plusSeconds(integer(
        definition.source().get("durationSeconds"),
        1
    )));
    nodeRepository.save(node);
    waitingEvent(execution, node, null, now);
  }

  private void waitingEvent(
      final AiWorkflowExecution execution,
      final AiWorkflowNodeExecution node,
      final String childExecutionId,
      final Instant now
  ) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("nodeId", node.getNodeId());
    if (childExecutionId != null) {
      payload.put("childExecutionId", childExecutionId);
    }
    eventPublisher.publish(
        execution,
        WorkflowExecutionEventType.NODE_WAITING,
        node.getId(),
        null,
        null,
        payload,
        now
    );
  }

  private Eligibility eligibility(
      final NodeDefinition definition,
      final List<Edge> edges,
      final Map<String, AiWorkflowNodeExecution> nodes,
      final Map<String, Object> input,
      final Map<String, Object> outputs
  ) {
    List<Edge> incoming = edges.stream()
        .filter(edge -> definition.id().equals(edge.to()))
        .toList();
    if (incoming.isEmpty()) {
      return Eligibility.RUN;
    }
    for (Edge edge : incoming) {
      if (!NODE_TERMINAL.contains(nodes.get(edge.from()).getStatus())) {
        return Eligibility.BLOCKED;
      }
    }
    for (Edge edge : incoming) {
      AiWorkflowNodeExecution source = nodes.get(edge.from());
      if (source.getStatus() != WorkflowNodeExecutionStatus.SUCCEEDED
          && source.getStatus() != WorkflowNodeExecutionStatus.COMPENSATED) {
        continue;
      }
      if (edge.condition() == null
          || conditionEvaluator.evaluate(
              edge.condition(),
              input,
              outputs
          )) {
        return Eligibility.RUN;
      }
    }
    return Eligibility.SKIP;
  }

  private Object defaultInput(
      final String nodeId,
      final List<Edge> edges,
      final Map<String, AiWorkflowNodeExecution> nodes,
      final Map<String, Object> workflowInput,
      final Map<String, Object> outputs
  ) {
    List<String> sources = edges.stream()
        .filter(edge -> nodeId.equals(edge.to()))
        .map(Edge::from)
        .filter(source ->
            nodes.get(source).getStatus()
                == WorkflowNodeExecutionStatus.SUCCEEDED)
        .toList();
    if (sources.isEmpty()) {
      return workflowInput;
    }
    if (sources.size() == 1) {
      return outputs.get(sources.get(0));
    }
    Map<String, Object> result = new LinkedHashMap<>();
    sources.forEach(source -> result.put(source, outputs.get(source)));
    return result;
  }

  private void succeedNode(
      final AiWorkflowExecution execution,
      final AiWorkflowNodeExecution node,
      final Object output,
      final Instant now
  ) {
    String outputJson = write(output == null ? Map.of() : output);
    assertPayloadSize(outputJson, "Workflow node output");
    node.setStatus(WorkflowNodeExecutionStatus.SUCCEEDED);
    node.setOutputJson(outputJson);
    node.setOutputHash(sha256(outputJson));
    node.setCompletedAt(now);
    node.setErrorCode(null);
    node.setErrorMessage(null);
    nodeRepository.save(node);
    eventPublisher.publish(
        execution,
        WorkflowExecutionEventType.NODE_SUCCEEDED,
        node.getId(),
        null,
        null,
        Map.of("nodeId", node.getNodeId()),
        now
    );
  }

  private void skipNode(
      final AiWorkflowExecution execution,
      final AiWorkflowNodeExecution node,
      final Instant now
  ) {
    String outputJson = write(Map.of("skipped", true));
    node.setStatus(WorkflowNodeExecutionStatus.SKIPPED);
    node.setOutputJson(outputJson);
    node.setOutputHash(sha256(outputJson));
    node.setCompletedAt(now);
    nodeRepository.save(node);
    eventPublisher.publish(
        execution,
        WorkflowExecutionEventType.NODE_SKIPPED,
        node.getId(),
        null,
        null,
        Map.of("nodeId", node.getNodeId()),
        now
    );
  }

  private void failNode(
      final AiWorkflowExecution execution,
      final AiWorkflowNodeExecution node,
      final String code,
      final String message,
      final Instant now
  ) {
    StableDiagnostic diagnostic = AiExecutionDiagnostics.workflowNode(code);
    node.setStatus(WorkflowNodeExecutionStatus.FAILED);
    node.setErrorCode(diagnostic.errorCode());
    node.setErrorMessage(diagnostic.errorMessage());
    node.setCompletedAt(now);
    nodeRepository.save(node);
    eventPublisher.publish(
        execution,
        WorkflowExecutionEventType.NODE_FAILED,
        node.getId(),
        null,
        null,
        Map.of(
            "nodeId",
            node.getNodeId(),
            "code",
            node.getErrorCode()
        ),
        now
    );
  }

  private void failExecution(
      final AiWorkflowExecution execution,
      final Plan plan,
      final Map<String, AiWorkflowNodeExecution> nodes,
      final String code,
      final String message,
      final Instant now
  ) {
    StableDiagnostic diagnostic =
        AiExecutionDiagnostics.workflowExecution(
            blank(execution.getErrorCode())
                ? code : execution.getErrorCode()
        );
    execution.setErrorCode(diagnostic.errorCode());
    execution.setErrorMessage(diagnostic.errorMessage());
    terminationCoordinator.terminate(
        execution,
        nodes.values(),
        "workflow-runtime",
        diagnostic.errorCode(),
        now
    );
    if ("REVERSE_SUCCEEDED".equals(plan.compensationMode())
        && compensatable(nodes.values())) {
      execution.setStatus(WorkflowExecutionStatus.COMPENSATING);
      execution.setNextPollAt(now);
      execution.setInactiveSince(null);
      eventPublisher.publish(
          execution,
          WorkflowExecutionEventType.COMPENSATION_STARTED,
          null,
          null,
          null,
          Map.of("cause", execution.getErrorCode()),
          now
      );
      advanceCompensation(
          execution,
          plan,
          nodes,
          readMap(execution.getInputJson(), "Workflow input"),
          outputs(new ArrayList<>(nodes.values())),
          now
      );
      return;
    }
    finalizeFailure(execution, now);
  }

  private void advanceCompensation(
      final AiWorkflowExecution execution,
      final Plan plan,
      final Map<String, AiWorkflowNodeExecution> nodes,
      final Map<String, Object> input,
      final Map<String, Object> outputs,
      final Instant now
  ) {
    if (!terminationCoordinator.forwardChildrenSettled(
        execution,
        nodes.values()
    )) {
      parkCompensation(execution, now);
      return;
    }
    Optional<AiWorkflowNodeExecution> active = nodes.values().stream()
        .filter(node -> node.getStatus()
            == WorkflowNodeExecutionStatus.COMPENSATING)
        .findFirst();
    if (active.isPresent()) {
      parkCompensation(execution, now);
      return;
    }
    Optional<AiWorkflowNodeExecution> failed = nodes.values().stream()
        .filter(node -> node.getStatus()
            == WorkflowNodeExecutionStatus.COMPENSATION_FAILED)
        .findFirst();
    if (failed.isPresent()) {
      finalizeFailure(execution, now);
      return;
    }
    Optional<AiWorkflowNodeExecution> next = nodes.values().stream()
        .filter(node ->
            node.getStatus() == WorkflowNodeExecutionStatus.SUCCEEDED)
        .filter(node -> node.getCompensationSkillId() != null)
        .max(Comparator.comparing(
            AiWorkflowNodeExecution::getNodeOrder
        ));
    if (next.isEmpty()) {
      finalizeFailure(execution, now);
      return;
    }
    AiWorkflowNodeExecution node = next.orElseThrow();
    NodeDefinition definition = plan.node(node.getNodeId());
    Map<String, Object> compensation =
        mapOptional(definition.source().get("compensation"));
    Object template = compensation == null
        ? null : compensation.get("input");
    Object resolved = template == null
        ? Map.of(
            "workflowInput",
            input,
            "nodeInput",
            readObject(node.getInputJson(), "Node input"),
            "nodeOutput",
            readObject(node.getOutputJson(), "Node output")
        )
        : valueResolver.resolve(template, input, outputs);
    AiWorkflowChildLaunchCoordinator.NodeLaunchResult result =
        childLaunchCoordinator.launchCompensation(
            execution.getId(),
            node.getNodeId(),
            new SkillWorkflowExecutionCommand(
                node.getCompensationSkillId(),
                node.getCompensationSkillVersionId(),
                node.getCompensationContentHash(),
                execution.getScopeType(),
                execution.getTenantId(),
                execution.getRequestedBy(),
                execution.getId() + ":compensate:" + node.getNodeId(),
                requireObject(resolved, "Compensation input")
            )
        );
    synchronizeCompensationLaunch(node, result.node());
    eventPublisher.publish(
        execution,
        WorkflowExecutionEventType.COMPENSATION_STARTED,
        node.getId(),
        null,
        null,
        Map.of(
            "nodeId",
            node.getNodeId(),
            "childExecutionId",
            node.getCompensationExecutionId()
        ),
        now
    );
    parkCompensation(execution, now);
  }

  private static void synchronizeCompensationLaunch(
      final AiWorkflowNodeExecution target,
      final AiWorkflowNodeExecution persisted
  ) {
    target.setStatus(persisted.getStatus());
    target.setCompensationExecutionId(
        persisted.getCompensationExecutionId()
    );
    target.setErrorCode(persisted.getErrorCode());
    target.setErrorMessage(persisted.getErrorMessage());
    sanitizeNodeDiagnostic(target);
  }

  private void refreshCompensation(
      final AiWorkflowExecution execution,
      final AiWorkflowNodeExecution node,
      final Instant now
  ) {
    AiSkillExecution child = skillExecutionRepository.findActiveById(
        node.getCompensationExecutionId()
    ).orElseThrow(() -> new IllegalStateException(
        "Workflow compensation execution disappeared"
    ));
    if (child.getStatus() == SkillExecutionStatus.SUCCEEDED) {
      node.setStatus(WorkflowNodeExecutionStatus.COMPENSATED);
      node.setCompletedAt(now);
      nodeRepository.save(node);
      eventPublisher.publish(
          execution,
          WorkflowExecutionEventType.COMPENSATION_SUCCEEDED,
          node.getId(),
          null,
          null,
          Map.of("nodeId", node.getNodeId()),
          now
      );
    } else if (Set.of(
        SkillExecutionStatus.FAILED,
        SkillExecutionStatus.REJECTED,
        SkillExecutionStatus.CANCELLED
    ).contains(child.getStatus())) {
      node.setStatus(WorkflowNodeExecutionStatus.COMPENSATION_FAILED);
      StableDiagnostic diagnostic = AiExecutionDiagnostics.workflowNode(
          "WORKFLOW_COMPENSATION_CHILD_" + child.getStatus().name()
      );
      node.setErrorCode(diagnostic.errorCode());
      node.setErrorMessage(diagnostic.errorMessage());
      node.setCompletedAt(now);
      nodeRepository.save(node);
      eventPublisher.publish(
          execution,
          WorkflowExecutionEventType.COMPENSATION_FAILED,
          node.getId(),
          null,
          null,
          Map.of(
              "nodeId",
              node.getNodeId(),
              "code",
              node.getErrorCode()
          ),
          now
      );
    }
  }

  private void completeExecution(
      final AiWorkflowExecution execution,
      final Plan plan,
      final Map<String, Object> input,
      final Map<String, Object> outputs,
      final Instant now
  ) {
    Object output = plan.outputTemplate() == null
        ? outputs : valueResolver.resolve(
            plan.outputTemplate(),
            input,
            outputs
        );
    Map<String, Object> outputObject =
        requireObject(output, "Workflow output");
    schemaValidator.validate(
        plan.outputSchema(),
        outputObject,
        "Workflow output"
    );
    String outputJson = write(outputObject);
    assertPayloadSize(outputJson, "Workflow output");
    execution.setOutputJson(outputJson);
    execution.setStatus(WorkflowExecutionStatus.SUCCEEDED);
    execution.setCompletedAt(now);
    execution.setInactiveSince(null);
    clearLease(execution);
    eventPublisher.publish(
        execution,
        WorkflowExecutionEventType.EXECUTION_SUCCEEDED,
        null,
        null,
        null,
        Map.of(),
        now
    );
  }

  private void finalizeFailure(
      final AiWorkflowExecution execution,
      final Instant now
  ) {
    execution.setStatus(WorkflowExecutionStatus.FAILED);
    execution.setCompletedAt(now);
    execution.setInactiveSince(null);
    clearLease(execution);
    eventPublisher.publish(
        execution,
        WorkflowExecutionEventType.EXECUTION_FAILED,
        null,
        null,
        null,
        Map.of("code", optional(
            execution.getErrorCode(),
            "WORKFLOW_EXECUTION_FAILED"
        )),
        now
    );
  }

  private void park(
      final AiWorkflowExecution execution,
      final java.util.Collection<AiWorkflowNodeExecution> nodes,
      final Instant now
  ) {
    Instant next = null;
    boolean child = false;
    boolean human = false;
    boolean timer = false;
    for (AiWorkflowNodeExecution node : nodes) {
      if (node.getStatus() != WorkflowNodeExecutionStatus.WAITING) {
        continue;
      }
      switch (node.getNodeType()) {
        case "agent", "skill" -> {
          child = true;
          next = minimum(next, now.plus(childPollInterval()));
        }
        case "human" -> {
          human = true;
          next = minimum(next, node.getScheduledAt());
        }
        case "wait" -> {
          timer = true;
          next = minimum(next, node.getScheduledAt());
        }
        default -> {
        }
      }
    }
    if (child) {
      execution.setStatus(WorkflowExecutionStatus.WAITING_CHILD);
    } else if (human) {
      execution.setStatus(WorkflowExecutionStatus.WAITING_HUMAN);
    } else if (timer) {
      execution.setStatus(WorkflowExecutionStatus.WAITING_TIMER);
    } else {
      throw new IllegalStateException(
          "Workflow has pending nodes but no runnable checkpoint"
      );
    }
    execution.setNextPollAt(next == null ? now.plusSeconds(1) : next);
    execution.setInactiveSince(now);
    clearLeaseOnly(execution);
  }

  private void parkCompensation(
      final AiWorkflowExecution execution,
      final Instant now
  ) {
    execution.setStatus(WorkflowExecutionStatus.COMPENSATING);
    execution.setNextPollAt(now.plus(childPollInterval()));
    execution.setInactiveSince(now);
    clearLeaseOnly(execution);
  }

  private void pause(
      final AiWorkflowExecution execution,
      final Instant now
  ) {
    execution.setStatus(WorkflowExecutionStatus.PAUSED);
    execution.setPausedAt(now);
    execution.setInactiveSince(now);
    clearLease(execution);
    eventPublisher.publish(
        execution,
        WorkflowExecutionEventType.EXECUTION_PAUSED,
        null,
        null,
        null,
        Map.of(),
        now
    );
  }

  private AiWorkflowExecution requireOwned(final ExecutionTask task) {
    AiWorkflowExecution execution =
        executionRepository.findActiveByIdForUpdate(task.executionId())
            .orElseThrow(() -> new StaleWorkflowLeaseException(
                "Workflow execution disappeared"
            ));
    Instant now = Instant.now();
    if (!task.workerId().equals(execution.getLeaseOwner())
        || task.leaseToken() != execution.getLeaseToken()
        || execution.getLeaseExpiresAt() == null
        || !now.isBefore(execution.getLeaseExpiresAt())) {
      throw new StaleWorkflowLeaseException(
          "Workflow execution lease is stale"
      );
    }
    return execution;
  }

  private Plan plan(final AiWorkflowExecution execution) {
    Map<String, Object> snapshot = readMap(
        execution.getPlanJson(),
        "Workflow execution plan"
    );
    Map<String, Object> manifest =
        map(snapshot.get("manifest"), "Workflow manifest");
    Map<String, Object> spec =
        map(manifest.get("spec"), "Workflow spec");
    List<NodeDefinition> nodes = mapList(spec.get("nodes")).stream()
        .map(source -> new NodeDefinition(
            String.valueOf(source.get("id")),
            String.valueOf(source.get("type")).toLowerCase(Locale.ROOT),
            source
        ))
        .toList();
    List<Edge> edges = spec.get("edges") == null
        ? List.of() : mapList(spec.get("edges")).stream()
            .map(source -> new Edge(
                String.valueOf(source.get("from")),
                String.valueOf(source.get("to")),
                source.get("condition")
            ))
            .toList();
    List<Dependency> dependencies =
        mapList(snapshot.get("dependencies")).stream()
            .map(source -> new Dependency(
                String.valueOf(source.get("nodeId")),
                String.valueOf(source.get("type")),
                String.valueOf(source.get("resourceId")),
                String.valueOf(source.get("resourceVersionId")),
                String.valueOf(source.get("resourceContentHash"))
            ))
            .toList();
    Map<String, Object> failure =
        mapOptional(spec.get("failurePolicy"));
    return new Plan(
        nodes,
        edges,
        dependencies,
        spec.get("output"),
        map(spec.get("outputSchema"), "Workflow output schema"),
        failure == null
            ? "FAIL_FAST" : String.valueOf(
                failure.getOrDefault("mode", "FAIL_FAST")
            ).toUpperCase(Locale.ROOT),
        failure == null
            ? "NONE" : String.valueOf(
                failure.getOrDefault("compensation", "NONE")
            ).toUpperCase(Locale.ROOT)
    );
  }

  private Map<String, Object> outputs(
      final List<AiWorkflowNodeExecution> nodes
  ) {
    Map<String, Object> outputs = new LinkedHashMap<>();
    for (AiWorkflowNodeExecution node : nodes) {
      if (node.getOutputJson() != null) {
        outputs.put(node.getNodeId(), readObject(
            node.getOutputJson(),
            "Workflow node output"
        ));
      }
    }
    return outputs;
  }

  private static int activeNodes(
      final java.util.Collection<AiWorkflowNodeExecution> nodes
  ) {
    return (int) nodes.stream()
        .filter(node ->
            node.getStatus() == WorkflowNodeExecutionStatus.RUNNING
                || node.getStatus() == WorkflowNodeExecutionStatus.WAITING)
        .count();
  }

  private static boolean compensatable(
      final java.util.Collection<AiWorkflowNodeExecution> nodes
  ) {
    return nodes.stream().anyMatch(node ->
        node.getStatus() == WorkflowNodeExecutionStatus.SUCCEEDED
            && node.getCompensationSkillId() != null);
  }

  private void reserveNodeExecution(
      final AiWorkflowExecution execution
  ) {
    int consumed = execution.getConsumedNodeExecutions() + 1;
    if (consumed > execution.getMaximumNodeExecutions()) {
      throw new IllegalStateException(
          "Workflow node execution budget was exhausted"
      );
    }
    execution.setConsumedNodeExecutions(consumed);
  }

  private void assertPayloadSize(
      final String json,
      final String label
  ) {
    int maximum = properties.getMaximumPayloadBytes() == null
        ? 1024 * 1024 : properties.getMaximumPayloadBytes();
    if (json.getBytes(StandardCharsets.UTF_8).length > maximum) {
      throw new IllegalArgumentException(label + " is too large");
    }
  }

  private Duration childPollInterval() {
    Duration value = properties.getChildPollInterval();
    return value == null || value.isNegative() || value.isZero()
        ? Duration.ofMillis(500) : value;
  }

  private static Instant minimum(
      final Instant first,
      final Instant second
  ) {
    if (second == null) {
      return first;
    }
    return first == null || second.isBefore(first) ? second : first;
  }

  private static boolean terminal(final WorkflowExecutionStatus status) {
    return status == WorkflowExecutionStatus.SUCCEEDED
        || status == WorkflowExecutionStatus.FAILED
        || status == WorkflowExecutionStatus.CANCELLED;
  }

  private static void clearLease(
      final AiWorkflowExecution execution
  ) {
    execution.setLeaseOwner(null);
    execution.setLeaseExpiresAt(null);
    execution.setNextPollAt(null);
  }

  private static void clearLeaseOnly(
      final AiWorkflowExecution execution
  ) {
    execution.setLeaseOwner(null);
    execution.setLeaseExpiresAt(null);
  }

  private String write(final Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException(
          "Workflow JSON is not serializable",
          ex
      );
    }
  }

  private Map<String, Object> readMap(
      final String value,
      final String label
  ) {
    try {
      return objectMapper.readValue(value, MAP_TYPE);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(label + " is invalid", ex);
    }
  }

  private Object readObject(
      final String value,
      final String label
  ) {
    if (value == null) {
      return null;
    }
    try {
      return objectMapper.readValue(value, Object.class);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(label + " is invalid", ex);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(
      final Object value,
      final String label
  ) {
    if (!(value instanceof Map<?, ?>)) {
      throw new IllegalStateException(label + " must be an object");
    }
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mapOptional(final Object value) {
    return value instanceof Map<?, ?>
        ? (Map<String, Object>) value : null;
  }

  private static Map<String, Object> castMap(final Map<?, ?> value) {
    Map<String, Object> result = new LinkedHashMap<>();
    value.forEach((key, item) -> result.put(String.valueOf(key), item));
    return result;
  }

  private static List<Map<String, Object>> mapList(final Object value) {
    if (!(value instanceof List<?> list)) {
      throw new IllegalStateException(
          "Workflow plan array is invalid"
      );
    }
    List<Map<String, Object>> result = new ArrayList<>(list.size());
    for (Object item : list) {
      result.add(map(item, "Workflow plan item"));
    }
    return result;
  }

  private static Map<String, Object> requireObject(
      final Object value,
      final String label
  ) {
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException(label + " must be an object");
    }
    return castMap(map);
  }

  private static int integer(
      final Object value,
      final int fallback
  ) {
    return value instanceof Number number ? number.intValue() : fallback;
  }

  private static String text(final Object value) {
    return value == null ? null : bounded(String.valueOf(value));
  }

  private static String optional(
      final String value,
      final String fallback
  ) {
    return value == null || value.isBlank() ? fallback : bounded(value);
  }

  private static boolean blank(final String value) {
    return value == null || value.isBlank();
  }

  private static void sanitizeExecutionDiagnostic(
      final AiWorkflowExecution execution
  ) {
    if (blank(execution.getErrorCode())
        && blank(execution.getErrorMessage())) {
      return;
    }
    StableDiagnostic diagnostic =
        AiExecutionDiagnostics.workflowExecution(
            execution.getErrorCode()
        );
    execution.setErrorCode(diagnostic.errorCode());
    execution.setErrorMessage(diagnostic.errorMessage());
  }

  private static void sanitizeNodeDiagnostic(
      final AiWorkflowNodeExecution node
  ) {
    if (blank(node.getErrorCode()) && blank(node.getErrorMessage())) {
      return;
    }
    StableDiagnostic diagnostic = AiExecutionDiagnostics.workflowNode(
        node.getErrorCode()
    );
    node.setErrorCode(diagnostic.errorCode());
    node.setErrorMessage(diagnostic.errorMessage());
  }

  private static String bounded(final String value) {
    String result = value == null || value.isBlank()
        ? "Workflow operation failed" : value.trim();
    return result.length() <= 1024 ? result : result.substring(0, 1024);
  }

  private static String sha256(final String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256")
              .digest(value.getBytes(StandardCharsets.UTF_8))
      );
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }

  private enum Eligibility {
    RUN,
    SKIP,
    BLOCKED
  }

  private record NodeDefinition(
      String id,
      String type,
      Map<String, Object> source
  ) {
  }

  private record Edge(String from, String to, Object condition) {
  }

  private record Dependency(
      String nodeId,
      String type,
      String resourceId,
      String resourceVersionId,
      String contentHash
  ) {
  }

  private record Plan(
      List<NodeDefinition> nodes,
      List<Edge> edges,
      List<Dependency> dependencies,
      Object outputTemplate,
      Map<String, Object> outputSchema,
      String failureMode,
      String compensationMode
  ) {

    private Dependency dependency(
        final String nodeId,
        final String type
    ) {
      return dependencies.stream()
          .filter(value -> nodeId.equals(value.nodeId())
              && type.equals(value.type()))
          .findFirst()
          .orElseThrow(() -> new IllegalStateException(
              "Workflow pinned dependency is missing"
          ));
    }

    private NodeDefinition node(final String nodeId) {
      return nodes.stream()
          .filter(value -> nodeId.equals(value.id()))
          .findFirst()
          .orElseThrow(() -> new IllegalStateException(
              "Workflow node definition is missing"
          ));
    }
  }
}
