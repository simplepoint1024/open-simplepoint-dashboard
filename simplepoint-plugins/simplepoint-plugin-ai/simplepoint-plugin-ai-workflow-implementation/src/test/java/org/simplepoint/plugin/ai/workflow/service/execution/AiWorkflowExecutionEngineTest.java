package org.simplepoint.plugin.ai.workflow.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.ai.agent.api.model.AgentWorkflowExecutionCommand;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentExecutionRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
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
import org.simplepoint.plugin.ai.workflow.service.execution.AiWorkflowChildLaunchCoordinator.NodeLaunchResult;
import org.simplepoint.plugin.ai.workflow.service.execution.AiWorkflowExecutionCoordinator.ExecutionTask;
import org.simplepoint.plugin.ai.workflow.service.support.WorkflowConditionEvaluator;
import org.simplepoint.plugin.ai.workflow.service.support.WorkflowValueResolver;

class AiWorkflowExecutionEngineTest {

  private static final String CONTENT_HASH = "a".repeat(64);

  private AiWorkflowExecutionRepository executionRepository;

  private AiWorkflowNodeExecutionRepository nodeRepository;

  private AiWorkflowHumanTaskRepository taskRepository;

  private AiAgentExecutionRepository agentExecutionRepository;

  private AiSkillExecutionRepository skillExecutionRepository;

  private AiWorkflowChildLaunchCoordinator childLaunchCoordinator;

  private AiWorkflowTerminationCoordinator terminationCoordinator;

  private AiWorkflowExecutionEventPublisher eventPublisher;

  private AiWorkflowExecutionEngine engine;

  @BeforeEach
  void setUp() {
    executionRepository = mock(AiWorkflowExecutionRepository.class);
    nodeRepository = mock(AiWorkflowNodeExecutionRepository.class);
    taskRepository = mock(AiWorkflowHumanTaskRepository.class);
    agentExecutionRepository = mock(AiAgentExecutionRepository.class);
    skillExecutionRepository = mock(AiSkillExecutionRepository.class);
    childLaunchCoordinator = mock(AiWorkflowChildLaunchCoordinator.class);
    terminationCoordinator = mock(AiWorkflowTerminationCoordinator.class);
    eventPublisher = mock(AiWorkflowExecutionEventPublisher.class);
    final WorkflowValueResolver resolver = new WorkflowValueResolver();
    when(executionRepository.save(any(AiWorkflowExecution.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(nodeRepository.save(any(AiWorkflowNodeExecution.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(terminationCoordinator.forwardChildrenSettled(any(), any()))
        .thenReturn(true);
    engine = new AiWorkflowExecutionEngine(
        executionRepository,
        nodeRepository,
        taskRepository,
        agentExecutionRepository,
        skillExecutionRepository,
        childLaunchCoordinator,
        terminationCoordinator,
        eventPublisher,
        resolver,
        new WorkflowConditionEvaluator(resolver),
        new SkillJsonSchemaValidator(),
        new WorkflowExecutionProperties(),
        JsonMapper.builder().build()
    );
  }

  @Test
  void routesAgentLaunchWithoutSavingNodeBeforeCoordinator() {
    AiWorkflowExecution execution = execution("agent", "AGENT");
    AiWorkflowNodeExecution node = pendingNode("agent");
    stubAdvance(execution, node);
    AiWorkflowNodeExecution persisted = associatedNode(
        node,
        "AGENT",
        "agent-1",
        "agent-version-1",
        "agent-execution-1"
    );
    when(childLaunchCoordinator.launchAgent(
        eq("workflow-execution-1"),
        eq("node-1"),
        anyString(),
        any(Instant.class),
        any(AgentWorkflowExecutionCommand.class)
    )).thenReturn(new NodeLaunchResult(persisted, true));

    engine.advance(task());

    ArgumentCaptor<AgentWorkflowExecutionCommand> command =
        ArgumentCaptor.forClass(AgentWorkflowExecutionCommand.class);
    verify(childLaunchCoordinator).launchAgent(
        eq("workflow-execution-1"),
        eq("node-1"),
        eq("{\"request\":\"hello\"}"),
        any(Instant.class),
        command.capture()
    );
    assertThat(command.getValue().idempotencyKey())
        .isEqualTo("workflow-execution-1:node:node-1");
    assertThat(command.getValue().agentId()).isEqualTo("agent-1");
    assertThat(node.getStatus())
        .isEqualTo(WorkflowNodeExecutionStatus.WAITING);
    assertThat(node.getChildExecutionId()).isEqualTo("agent-execution-1");
    assertThat(execution.getStatus())
        .isEqualTo(WorkflowExecutionStatus.WAITING_CHILD);
    verify(nodeRepository, never()).save(node);
    verifyLifecycleEvents();
  }

  @Test
  void routesSkillLaunchThroughAtomicCoordinator() {
    AiWorkflowExecution execution = execution("skill", "SKILL");
    AiWorkflowNodeExecution node = pendingNode("skill");
    stubAdvance(execution, node);
    AiWorkflowNodeExecution persisted = associatedNode(
        node,
        "SKILL",
        "skill-1",
        "skill-version-1",
        "skill-execution-1"
    );
    when(childLaunchCoordinator.launchSkill(
        eq("workflow-execution-1"),
        eq("node-1"),
        anyString(),
        any(Instant.class),
        any(SkillWorkflowExecutionCommand.class)
    )).thenReturn(new NodeLaunchResult(persisted, true));

    engine.advance(task());

    ArgumentCaptor<SkillWorkflowExecutionCommand> command =
        ArgumentCaptor.forClass(SkillWorkflowExecutionCommand.class);
    verify(childLaunchCoordinator).launchSkill(
        eq("workflow-execution-1"),
        eq("node-1"),
        eq("{\"request\":\"hello\"}"),
        any(Instant.class),
        command.capture()
    );
    assertThat(command.getValue().idempotencyKey())
        .isEqualTo("workflow-execution-1:node:node-1");
    assertThat(node.getChildExecutionId()).isEqualTo("skill-execution-1");
    verify(nodeRepository, never()).save(node);
    verifyLifecycleEvents();
  }

  @Test
  void recordsLaunchFailureInOuterWorkflowTransaction() {
    String sentinel =
        "provider body token=sk-live-secret https://internal.example";
    AiWorkflowExecution execution = execution("agent", "AGENT");
    AiWorkflowNodeExecution node = pendingNode("agent");
    stubAdvance(execution, node);
    when(childLaunchCoordinator.launchAgent(
        eq("workflow-execution-1"),
        eq("node-1"),
        anyString(),
        any(Instant.class),
        any(AgentWorkflowExecutionCommand.class)
    )).thenThrow(new IllegalStateException(sentinel));

    engine.advance(task());

    assertThat(node.getStatus())
        .isEqualTo(WorkflowNodeExecutionStatus.FAILED);
    assertThat(node.getAttemptCount()).isEqualTo(1);
    assertThat(node.getInputJson())
        .isEqualTo("{\"request\":\"hello\"}");
    assertThat(node.getErrorCode())
        .isEqualTo("WORKFLOW_NODE_EXECUTION_FAILED");
    assertThat(node.getErrorMessage())
        .isEqualTo("WORKFLOW_NODE_EXECUTION_FAILED");
    assertThat(execution.getStatus())
        .isEqualTo(WorkflowExecutionStatus.FAILED);
    assertThat(execution.getErrorCode())
        .isEqualTo("WORKFLOW_NODE_EXECUTION_FAILED");
    assertThat(execution.getErrorMessage())
        .isEqualTo("WORKFLOW_NODE_EXECUTION_FAILED");
    assertThat(node.getErrorCode() + node.getErrorMessage()
        + execution.getErrorCode() + execution.getErrorMessage())
        .doesNotContain(sentinel);
    verify(nodeRepository).save(node);
    verify(executionRepository).save(execution);
    verify(terminationCoordinator).terminate(
        eq(execution),
        sameNodes(node),
        eq("workflow-runtime"),
        eq("WORKFLOW_NODE_EXECUTION_FAILED"),
        any(Instant.class)
    );
  }

  @Test
  void routesCompensationThroughAtomicCoordinator() {
    AiWorkflowExecution execution = execution("agent", "AGENT");
    execution.setStatus(WorkflowExecutionStatus.COMPENSATING);
    AiWorkflowNodeExecution node = pendingNode("agent");
    node.setStatus(WorkflowNodeExecutionStatus.SUCCEEDED);
    node.setInputJson("{}");
    node.setOutputJson("{}");
    node.setCompensationSkillId("skill-1");
    node.setCompensationSkillVersionId("skill-version-1");
    node.setCompensationContentHash(CONTENT_HASH);
    execution.setPlanJson(compensationPlan());
    stubAdvance(execution, node);
    AiWorkflowNodeExecution persisted = copyNode(node);
    persisted.setStatus(WorkflowNodeExecutionStatus.COMPENSATING);
    persisted.setCompensationExecutionId("compensation-execution-1");
    when(childLaunchCoordinator.launchCompensation(
        eq("workflow-execution-1"),
        eq("node-1"),
        any(SkillWorkflowExecutionCommand.class)
    )).thenReturn(new NodeLaunchResult(persisted, true));

    engine.advance(task());

    ArgumentCaptor<SkillWorkflowExecutionCommand> command =
        ArgumentCaptor.forClass(SkillWorkflowExecutionCommand.class);
    verify(childLaunchCoordinator).launchCompensation(
        eq("workflow-execution-1"),
        eq("node-1"),
        command.capture()
    );
    assertThat(command.getValue().idempotencyKey())
        .isEqualTo("workflow-execution-1:compensate:node-1");
    assertThat(node.getStatus())
        .isEqualTo(WorkflowNodeExecutionStatus.COMPENSATING);
    assertThat(node.getCompensationExecutionId())
        .isEqualTo("compensation-execution-1");
    verify(nodeRepository, never()).save(node);
    verify(eventPublisher).publish(
        eq(execution),
        eq(WorkflowExecutionEventType.COMPENSATION_STARTED),
        eq("node-execution-1"),
        any(),
        any(),
        any(),
        any(Instant.class)
    );
  }

  @Test
  void humanTimeoutCancelTerminatesParallelWorkAndUsesRuntimeActor() {
    AiWorkflowExecution execution = execution("skill", "SKILL");
    execution.setPlanJson(parallelPlan());
    AiWorkflowNodeExecution human = pendingNode("human");
    human.setNodeId("human-node");
    human.setStatus(WorkflowNodeExecutionStatus.WAITING);
    human.setScheduledAt(Instant.now().minusSeconds(5));
    AiWorkflowNodeExecution skill = pendingNode("skill");
    skill.setId("node-execution-2");
    skill.setNodeId("skill-node");
    skill.setNodeOrder(1);
    skill.setStatus(WorkflowNodeExecutionStatus.WAITING);
    skill.setChildType("SKILL");
    skill.setChildResourceId("skill-1");
    skill.setChildResourceVersionId("skill-version-1");
    skill.setChildExecutionId("skill-execution-1");
    AiWorkflowHumanTask humanTask = new AiWorkflowHumanTask();
    humanTask.setId("human-task-1");
    humanTask.setExecutionId(execution.getId());
    humanTask.setNodeExecutionId(human.getId());
    humanTask.setNodeId(human.getNodeId());
    humanTask.setStatus(WorkflowHumanTaskStatus.OPEN);
    humanTask.setDueAt(Instant.now().minusSeconds(1));
    humanTask.setTimeoutAction("CANCEL");
    when(taskRepository.findActiveByNodeExecutionId(human.getId()))
        .thenReturn(Optional.of(humanTask));
    when(taskRepository.save(humanTask)).thenReturn(humanTask);
    AiSkillExecution running = new AiSkillExecution();
    running.setStatus(SkillExecutionStatus.RUNNING);
    when(skillExecutionRepository.findActiveById("skill-execution-1"))
        .thenReturn(Optional.of(running));
    stubAdvance(execution, List.of(human, skill));

    engine.advance(task());

    assertThat(execution.getStatus())
        .isEqualTo(WorkflowExecutionStatus.CANCELLED);
    assertThat(humanTask.getStatus())
        .isEqualTo(WorkflowHumanTaskStatus.TIMED_OUT);
    verify(terminationCoordinator).terminate(
        eq(execution),
        sameNodes(human, skill),
        eq("workflow-runtime"),
        eq("WORKFLOW_HUMAN_TASK_TIMEOUT"),
        any(Instant.class)
    );
    verify(eventPublisher).publish(
        eq(execution),
        eq(WorkflowExecutionEventType.EXECUTION_CANCELLED),
        eq(human.getId()),
        eq(humanTask.getId()),
        eq("workflow-runtime"),
        any(),
        any(Instant.class)
    );
  }

  @Test
  void deadlineFailureTerminatesAllUnfinishedWork() {
    AiWorkflowExecution execution = execution("agent", "AGENT");
    execution.setDeadlineAt(Instant.now().minusSeconds(1));
    AiWorkflowNodeExecution node = pendingNode("agent");
    stubAdvance(execution, node);

    engine.advance(task());

    assertThat(execution.getStatus()).isEqualTo(WorkflowExecutionStatus.FAILED);
    assertThat(execution.getErrorCode())
        .isEqualTo("WORKFLOW_BUDGET_TIME_EXCEEDED");
    verify(terminationCoordinator).terminate(
        eq(execution),
        sameNodes(node),
        eq("workflow-runtime"),
        eq("WORKFLOW_BUDGET_TIME_EXCEEDED"),
        any(Instant.class)
    );
  }

  @Test
  void failFastPreservesRootFailureWhileTerminatingParallelChild() {
    AiWorkflowExecution execution = execution("skill", "SKILL");
    execution.setPlanJson(parallelPlan());
    AiWorkflowNodeExecution root = pendingNode("human");
    root.setNodeId("human-node");
    root.setStatus(WorkflowNodeExecutionStatus.FAILED);
    root.setErrorCode("ROOT_VALIDATION_FAILED");
    root.setErrorMessage("root cause");
    AiWorkflowNodeExecution parallel = pendingNode("skill");
    parallel.setId("node-execution-2");
    parallel.setNodeId("skill-node");
    parallel.setNodeOrder(1);
    parallel.setStatus(WorkflowNodeExecutionStatus.WAITING);
    parallel.setChildType("SKILL");
    parallel.setChildResourceId("skill-1");
    parallel.setChildResourceVersionId("skill-version-1");
    parallel.setChildExecutionId("skill-execution-1");
    AiSkillExecution running = new AiSkillExecution();
    running.setStatus(SkillExecutionStatus.RUNNING);
    when(skillExecutionRepository.findActiveById("skill-execution-1"))
        .thenReturn(Optional.of(running));
    stubAdvance(execution, List.of(root, parallel));

    engine.advance(task());

    assertThat(execution.getStatus()).isEqualTo(WorkflowExecutionStatus.FAILED);
    assertThat(execution.getErrorCode())
        .isEqualTo("WORKFLOW_NODE_FAILED");
    assertThat(execution.getErrorMessage())
        .isEqualTo("WORKFLOW_NODE_FAILED");
    verify(terminationCoordinator).terminate(
        eq(execution),
        sameNodes(root, parallel),
        eq("workflow-runtime"),
        eq("WORKFLOW_NODE_FAILED"),
        any(Instant.class)
    );
  }

  @Test
  void nodeBudgetIsFatalEvenWhenPlanWouldOtherwiseContinue() {
    AiWorkflowExecution execution = execution("skill", "SKILL");
    execution.setPlanJson(continuePlan());
    execution.setMaximumNodeExecutions(0);
    AiWorkflowNodeExecution node = pendingNode("end");
    stubAdvance(execution, node);

    engine.advance(task());

    assertThat(execution.getStatus()).isEqualTo(WorkflowExecutionStatus.FAILED);
    assertThat(execution.getErrorCode())
        .isEqualTo("WORKFLOW_BUDGET_NODE_EXECUTIONS_EXCEEDED");
    verify(terminationCoordinator).terminate(
        eq(execution),
        sameNodes(node),
        eq("workflow-runtime"),
        eq("WORKFLOW_BUDGET_NODE_EXECUTIONS_EXCEEDED"),
        any(Instant.class)
    );
  }

  @Test
  void compensationParksUntilForwardChildrenSettleAndKeepsRootError() {
    String sentinel =
        "provider body token=sk-live-resume https://internal.example";
    AiWorkflowExecution execution = execution("agent", "AGENT");
    execution.setStatus(WorkflowExecutionStatus.COMPENSATING);
    execution.setErrorCode("ROOT_" + sentinel);
    execution.setErrorMessage(sentinel);
    execution.setPlanJson(twoNodeCompensationPlan());
    AiWorkflowNodeExecution candidate = pendingNode("end");
    candidate.setNodeId("candidate");
    candidate.setStatus(WorkflowNodeExecutionStatus.SUCCEEDED);
    candidate.setInputJson("{}");
    candidate.setOutputJson("{}");
    candidate.setCompensationSkillId("compensation-skill");
    candidate.setCompensationSkillVersionId("compensation-version");
    candidate.setCompensationContentHash(CONTENT_HASH);
    AiWorkflowNodeExecution unsettled = pendingNode("skill");
    unsettled.setId("node-execution-2");
    unsettled.setNodeId("unsettled");
    unsettled.setNodeOrder(1);
    unsettled.setStatus(WorkflowNodeExecutionStatus.CANCELLED);
    unsettled.setChildType("SKILL");
    unsettled.setChildResourceId("skill-1");
    unsettled.setChildResourceVersionId("skill-version-1");
    unsettled.setChildExecutionId("skill-execution-1");
    stubAdvance(execution, List.of(candidate, unsettled));
    when(terminationCoordinator.forwardChildrenSettled(any(), any()))
        .thenReturn(false, true, true);

    engine.advance(task());

    assertThat(execution.getStatus())
        .isEqualTo(WorkflowExecutionStatus.COMPENSATING);
    verify(childLaunchCoordinator, never()).launchCompensation(
        any(), any(), any()
    );

    restoreLease(execution);
    AiWorkflowNodeExecution persisted = copyNode(candidate);
    persisted.setStatus(WorkflowNodeExecutionStatus.COMPENSATING);
    persisted.setCompensationExecutionId("compensation-execution-1");
    when(childLaunchCoordinator.launchCompensation(
        eq("workflow-execution-1"),
        eq("candidate"),
        any(SkillWorkflowExecutionCommand.class)
    )).thenReturn(new NodeLaunchResult(persisted, true));

    engine.advance(task());

    assertThat(candidate.getStatus())
        .isEqualTo(WorkflowNodeExecutionStatus.COMPENSATING);
    verify(childLaunchCoordinator).launchCompensation(
        eq("workflow-execution-1"),
        eq("candidate"),
        any(SkillWorkflowExecutionCommand.class)
    );

    restoreLease(execution);
    AiSkillExecution failedCompensation = new AiSkillExecution();
    failedCompensation.setStatus(SkillExecutionStatus.FAILED);
    failedCompensation.setErrorMessage("compensation transport failed");
    when(skillExecutionRepository.findActiveById(
        "compensation-execution-1"
    )).thenReturn(Optional.of(failedCompensation));

    engine.advance(task());

    assertThat(execution.getStatus()).isEqualTo(WorkflowExecutionStatus.FAILED);
    assertThat(execution.getErrorCode())
        .isEqualTo("WORKFLOW_EXECUTION_FAILED");
    assertThat(execution.getErrorMessage())
        .isEqualTo("WORKFLOW_EXECUTION_FAILED");
    assertThat(execution.getErrorCode() + execution.getErrorMessage())
        .doesNotContain(sentinel);
    assertThat(candidate.getStatus())
        .isEqualTo(WorkflowNodeExecutionStatus.COMPENSATION_FAILED);
    assertThat(candidate.getErrorCode())
        .isEqualTo("WORKFLOW_COMPENSATION_CHILD_FAILED");
    assertThat(candidate.getErrorMessage())
        .isEqualTo("WORKFLOW_COMPENSATION_CHILD_FAILED");
    assertThat(candidate.getOutputJson()).isEqualTo("{}");
    verify(terminationCoordinator, times(3)).forwardChildrenSettled(
        eq(execution), sameNodes(candidate, unsettled)
    );
  }

  private void stubAdvance(
      final AiWorkflowExecution execution,
      final AiWorkflowNodeExecution node
  ) {
    when(executionRepository.findActiveByIdForUpdate(
        "workflow-execution-1"
    )).thenReturn(Optional.of(execution));
    when(nodeRepository.findAllActiveByExecutionId(
        "workflow-execution-1"
    )).thenReturn(List.of(node));
  }

  private void stubAdvance(
      final AiWorkflowExecution execution,
      final List<AiWorkflowNodeExecution> nodes
  ) {
    when(executionRepository.findActiveByIdForUpdate(
        "workflow-execution-1"
    )).thenReturn(Optional.of(execution));
    when(nodeRepository.findAllActiveByExecutionId(
        "workflow-execution-1"
    )).thenReturn(nodes);
  }

  private static void restoreLease(final AiWorkflowExecution execution) {
    execution.setStatus(WorkflowExecutionStatus.COMPENSATING);
    execution.setLeaseOwner("worker-1");
    execution.setLeaseToken(7L);
    execution.setLeaseExpiresAt(Instant.now().plusSeconds(60));
  }

  private static Collection<AiWorkflowNodeExecution> sameNodes(
      final AiWorkflowNodeExecution... expected
  ) {
    return argThat(actual ->
        actual != null
            && List.copyOf(actual).equals(List.of(expected))
    );
  }

  private void verifyLifecycleEvents() {
    verify(eventPublisher).publish(
        any(AiWorkflowExecution.class),
        eq(WorkflowExecutionEventType.NODE_STARTED),
        eq("node-execution-1"),
        any(),
        any(),
        any(),
        any(Instant.class)
    );
    verify(eventPublisher).publish(
        any(AiWorkflowExecution.class),
        eq(WorkflowExecutionEventType.NODE_WAITING),
        eq("node-execution-1"),
        any(),
        any(),
        any(),
        any(Instant.class)
    );
    verify(eventPublisher, times(2)).publish(
        any(),
        any(),
        any(),
        any(),
        any(),
        any(),
        any()
    );
  }

  private static ExecutionTask task() {
    return new ExecutionTask(
        "workflow-execution-1",
        "worker-1",
        7L,
        WorkflowExecutionStatus.RUNNING
    );
  }

  private static AiWorkflowExecution execution(
      final String nodeType,
      final String dependencyType
  ) {
    AiWorkflowExecution execution = new AiWorkflowExecution();
    execution.setId("workflow-execution-1");
    execution.setStatus(WorkflowExecutionStatus.RUNNING);
    execution.setScopeType(AiResourceScope.TENANT);
    execution.setTenantId("tenant-1");
    execution.setRequestedBy("user-1");
    execution.setRequestContextId("context-1");
    execution.setInputJson("{\"request\":\"hello\"}");
    execution.setPlanJson(plan(nodeType, dependencyType));
    execution.setMaximumNodeExecutions(10);
    execution.setConsumedNodeExecutions(0);
    execution.setMaximumParallelism(2);
    execution.setDeadlineAt(Instant.now().plusSeconds(300));
    execution.setPauseRequested(false);
    execution.setLeaseOwner("worker-1");
    execution.setLeaseToken(7L);
    execution.setLeaseExpiresAt(Instant.now().plusSeconds(60));
    return execution;
  }

  private static AiWorkflowNodeExecution pendingNode(final String type) {
    AiWorkflowNodeExecution node = new AiWorkflowNodeExecution();
    node.setId("node-execution-1");
    node.setExecutionId("workflow-execution-1");
    node.setNodeId("node-1");
    node.setNodeType(type);
    node.setNodeOrder(0);
    node.setStatus(WorkflowNodeExecutionStatus.PENDING);
    node.setAttemptCount(0);
    return node;
  }

  private static AiWorkflowNodeExecution associatedNode(
      final AiWorkflowNodeExecution source,
      final String childType,
      final String resourceId,
      final String versionId,
      final String childExecutionId
  ) {
    AiWorkflowNodeExecution persisted = copyNode(source);
    persisted.setInputJson("{\"request\":\"hello\"}");
    persisted.setStatus(WorkflowNodeExecutionStatus.WAITING);
    persisted.setAttemptCount(1);
    persisted.setStartedAt(Instant.parse("2026-08-08T01:02:03Z"));
    persisted.setChildType(childType);
    persisted.setChildResourceId(resourceId);
    persisted.setChildResourceVersionId(versionId);
    persisted.setChildExecutionId(childExecutionId);
    return persisted;
  }

  private static AiWorkflowNodeExecution copyNode(
      final AiWorkflowNodeExecution source
  ) {
    AiWorkflowNodeExecution copy = new AiWorkflowNodeExecution();
    copy.setId(source.getId());
    copy.setExecutionId(source.getExecutionId());
    copy.setNodeId(source.getNodeId());
    copy.setNodeType(source.getNodeType());
    copy.setNodeOrder(source.getNodeOrder());
    copy.setStatus(source.getStatus());
    copy.setAttemptCount(source.getAttemptCount());
    copy.setInputJson(source.getInputJson());
    copy.setOutputJson(source.getOutputJson());
    copy.setCompensationSkillId(source.getCompensationSkillId());
    copy.setCompensationSkillVersionId(
        source.getCompensationSkillVersionId()
    );
    copy.setCompensationContentHash(source.getCompensationContentHash());
    return copy;
  }

  private static String plan(
      final String nodeType,
      final String dependencyType
  ) {
    String resourcePrefix = nodeType.equals("agent") ? "agent" : "skill";
    return """
        {
          "manifest": {"spec": {
            "nodes": [{"id": "node-1", "type": "%s"}],
            "edges": [],
            "outputSchema": {"type": "object", "additionalProperties": true}
          }},
          "dependencies": [{
            "nodeId": "node-1",
            "type": "%s",
            "resourceId": "%s-1",
            "resourceVersionId": "%s-version-1",
            "resourceContentHash": "%s"
          }]
        }
        """.formatted(
            nodeType,
            dependencyType,
            resourcePrefix,
            resourcePrefix,
            CONTENT_HASH
        );
  }

  private static String compensationPlan() {
    return """
        {
          "manifest": {"spec": {
            "nodes": [{
              "id": "node-1",
              "type": "agent",
              "compensation": {}
            }],
            "edges": [],
            "outputSchema": {"type": "object", "additionalProperties": true}
          }},
          "dependencies": []
        }
        """;
  }

  private static String parallelPlan() {
    return """
        {
          "manifest": {"spec": {
            "nodes": [
              {"id": "human-node", "type": "human"},
              {"id": "skill-node", "type": "skill"}
            ],
            "edges": [],
            "outputSchema": {"type": "object", "additionalProperties": true}
          }},
          "dependencies": [{
            "nodeId": "skill-node",
            "type": "SKILL",
            "resourceId": "skill-1",
            "resourceVersionId": "skill-version-1",
            "resourceContentHash": "%s"
          }]
        }
        """.formatted(CONTENT_HASH);
  }

  private static String continuePlan() {
    return """
        {
          "manifest": {"spec": {
            "nodes": [{"id": "node-1", "type": "end"}],
            "edges": [],
            "failurePolicy": {"mode": "CONTINUE", "compensation": "NONE"},
            "outputSchema": {"type": "object", "additionalProperties": true}
          }},
          "dependencies": []
        }
        """;
  }

  private static String twoNodeCompensationPlan() {
    return """
        {
          "manifest": {"spec": {
            "nodes": [
              {"id": "candidate", "type": "end", "compensation": {}},
              {"id": "unsettled", "type": "skill"}
            ],
            "edges": [],
            "failurePolicy": {
              "mode": "FAIL_FAST",
              "compensation": "REVERSE_SUCCEEDED"
            },
            "outputSchema": {"type": "object", "additionalProperties": true}
          }},
          "dependencies": []
        }
        """;
  }
}
