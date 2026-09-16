package org.simplepoint.plugin.ai.workflow.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecution;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecutionTrace;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentPinnedChildCancelCommand;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceType;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentExecutionRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentExecutionTraceRepository;
import org.simplepoint.plugin.ai.agent.api.service.AiAgentExecutionService;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecution;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionSource;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillPinnedChildCancelCommand;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillExecutionRepository;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillExecutionService;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowExecution;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowHumanTask;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowNodeExecution;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionEventType;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionStatus;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowHumanTaskStatus;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowNodeExecutionStatus;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowHumanTaskRepository;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowNodeExecutionRepository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class AiWorkflowTerminationCoordinatorTest {

  private AiWorkflowNodeExecutionRepository nodeRepository;

  private AiWorkflowHumanTaskRepository taskRepository;

  private AiAgentExecutionRepository agentRepository;

  private AiAgentExecutionTraceRepository traceRepository;

  private AiSkillExecutionRepository skillRepository;

  private AiAgentExecutionService agentService;

  private AiSkillExecutionService skillService;

  private AiWorkflowExecutionEventPublisher eventPublisher;

  private AiWorkflowTerminationCoordinator coordinator;

  @BeforeEach
  void setUp() {
    nodeRepository = mock(AiWorkflowNodeExecutionRepository.class);
    taskRepository = mock(AiWorkflowHumanTaskRepository.class);
    agentRepository = mock(AiAgentExecutionRepository.class);
    traceRepository = mock(AiAgentExecutionTraceRepository.class);
    skillRepository = mock(AiSkillExecutionRepository.class);
    agentService = mock(AiAgentExecutionService.class);
    skillService = mock(AiSkillExecutionService.class);
    eventPublisher = mock(AiWorkflowExecutionEventPublisher.class);
    when(nodeRepository.save(any(AiWorkflowNodeExecution.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(taskRepository.save(any(AiWorkflowHumanTask.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(taskRepository.findAllActiveByExecutionId("execution-1"))
        .thenReturn(List.of());
    coordinator = new AiWorkflowTerminationCoordinator(
        nodeRepository,
        taskRepository,
        agentRepository,
        traceRepository,
        skillRepository,
        agentService,
        skillService,
        eventPublisher
    );
  }

  @Test
  void cancelsChildrenInNodeOrderAndMapsRacingTerminalFacts() {
    final AiWorkflowExecution execution = execution();
    final AiWorkflowNodeExecution agent = childNode(
        0, "agent-node", "agent", "AGENT",
        "agent-1", "agent-version-1", "agent-execution-1"
    );
    final AiWorkflowNodeExecution skill = childNode(
        1, "skill-node", "skill", "SKILL",
        "skill-1", "skill-version-1", "skill-execution-1"
    );
    AiWorkflowNodeExecution compensation = node(
        2, "compensation-node", "skill",
        WorkflowNodeExecutionStatus.COMPENSATING
    );
    compensation.setCompensationSkillId("compensation-skill");
    compensation.setCompensationSkillVersionId("compensation-version");
    compensation.setCompensationContentHash("c".repeat(64));
    compensation.setCompensationExecutionId("compensation-execution");
    AiWorkflowNodeExecution terminal = childNode(
        3, "terminal-node", "skill", "SKILL",
        "terminal-skill", "terminal-version", "terminal-execution"
    );
    terminal.setStatus(WorkflowNodeExecutionStatus.SUCCEEDED);
    Instant terminalCompletedAt = Instant.parse("2026-08-08T01:00:00Z");
    terminal.setCompletedAt(terminalCompletedAt);
    AiWorkflowNodeExecution human = node(
        4, "human-node", "human", WorkflowNodeExecutionStatus.WAITING
    );
    AiWorkflowHumanTask task = humanTask(human);
    when(taskRepository.findAllActiveByExecutionId("execution-1"))
        .thenReturn(List.of(task));

    AiAgentExecution cancelledAgent = agentChild(
        "agent-1", "agent-version-1", "agent-execution-1",
        "execution-1:node:agent-node", AgentExecutionStatus.CANCELLED
    );
    cancelledAgent.setErrorCode("AGENT_PARENT_CANCELLED");
    when(agentService.cancelPinnedChild(any()))
        .thenReturn(cancelledAgent);
    when(agentRepository.findActiveById("agent-execution-1"))
        .thenReturn(Optional.of(cancelledAgent));
    AiSkillExecution racedSuccess = skillChild(
        "skill-1", "skill-version-1", "skill-execution-1",
        "execution-1:node:skill-node", SkillExecutionStatus.SUCCEEDED
    );
    Instant childCompletedAt = Instant.parse("2026-08-08T02:00:00Z");
    racedSuccess.setCompletedAt(childCompletedAt);
    racedSuccess.setOutputJson("{\"answer\":42}");
    AiSkillExecution runningCompensation = skillChild(
        "compensation-skill",
        "compensation-version",
        "compensation-execution",
        "execution-1:compensate:compensation-node",
        SkillExecutionStatus.RUNNING
    );
    when(skillService.cancelPinnedChild(any()))
        .thenReturn(racedSuccess, runningCompensation);
    when(skillRepository.findActiveById("skill-execution-1"))
        .thenReturn(Optional.of(racedSuccess));
    when(skillRepository.findActiveById("compensation-execution"))
        .thenReturn(Optional.of(runningCompensation));
    AiSkillExecution terminalSkill = skillChild(
        "terminal-skill",
        "terminal-version",
        "terminal-execution",
        "execution-1:node:terminal-node",
        SkillExecutionStatus.SUCCEEDED
    );
    terminalSkill.setOutputJson("{}");
    when(skillRepository.findActiveById("terminal-execution"))
        .thenReturn(Optional.of(terminalSkill));

    coordinator.terminate(
        execution,
        List.of(human, terminal, compensation, skill, agent),
        "user-1",
        "cancel requested",
        Instant.parse("2026-08-08T03:00:00Z")
    );

    InOrder order = inOrder(agentService, skillService);
    order.verify(agentService).cancelPinnedChild(any());
    order.verify(skillService, times(2)).cancelPinnedChild(any());
    ArgumentCaptor<AgentPinnedChildCancelCommand> agentCommand =
        ArgumentCaptor.forClass(AgentPinnedChildCancelCommand.class);
    verify(agentService).cancelPinnedChild(agentCommand.capture());
    assertThat(agentCommand.getValue().idempotencyKey())
        .isEqualTo("execution-1:node:agent-node");
    assertThat(agentCommand.getValue().executionScope())
        .isEqualTo(AiResourceScope.TENANT);
    ArgumentCaptor<SkillPinnedChildCancelCommand> skillCommands =
        ArgumentCaptor.forClass(SkillPinnedChildCancelCommand.class);
    verify(skillService, times(2)).cancelPinnedChild(
        skillCommands.capture()
    );
    assertThat(skillCommands.getAllValues())
        .extracting(SkillPinnedChildCancelCommand::idempotencyKey)
        .containsExactly(
            "execution-1:node:skill-node",
            "execution-1:compensate:compensation-node"
        );
    assertThat(agent.getStatus())
        .isEqualTo(WorkflowNodeExecutionStatus.CANCELLED);
    assertThat(agent.getErrorCode())
        .isEqualTo("WORKFLOW_AGENT_CHILD_CANCELLED");
    assertThat(agent.getErrorMessage())
        .isEqualTo("WORKFLOW_AGENT_CHILD_CANCELLED");
    assertThat(skill.getStatus())
        .isEqualTo(WorkflowNodeExecutionStatus.SUCCEEDED);
    assertThat(skill.getOutputJson()).isEqualTo("{\"answer\":42}");
    assertThat(skill.getCompletedAt()).isEqualTo(childCompletedAt);
    assertThat(compensation.getStatus())
        .isEqualTo(WorkflowNodeExecutionStatus.CANCELLED);
    assertThat(terminal.getStatus())
        .isEqualTo(WorkflowNodeExecutionStatus.SUCCEEDED);
    assertThat(terminal.getCompletedAt()).isEqualTo(terminalCompletedAt);
    assertThat(human.getStatus())
        .isEqualTo(WorkflowNodeExecutionStatus.CANCELLED);
    assertThat(task.getStatus()).isEqualTo(WorkflowHumanTaskStatus.CANCELLED);
    verify(eventPublisher, times(3)).publish(
        eq(execution),
        eq(WorkflowExecutionEventType.NODE_CANCELLED),
        any(),
        any(),
        eq("user-1"),
        any(),
        any()
    );
    verify(eventPublisher).publish(
        eq(execution),
        eq(WorkflowExecutionEventType.NODE_SUCCEEDED),
        eq(skill.getId()),
        any(),
        eq("user-1"),
        any(),
        any()
    );
    verify(eventPublisher).publish(
        eq(execution),
        eq(WorkflowExecutionEventType.HUMAN_TASK_CANCELLED),
        eq(human.getId()),
        eq(task.getId()),
        eq("user-1"),
        any(),
        any()
    );
  }

  @Test
  void preservesChildFailureCodeMessageAndCompletionTime() {
    final AiWorkflowExecution execution = execution();
    final AiWorkflowNodeExecution node = childNode(
        0, "agent-node", "agent", "AGENT",
        "agent-1", "version-1", "agent-execution-1"
    );
    AiAgentExecution failed = agentChild(
        "agent-1",
        "version-1",
        "agent-execution-1",
        "execution-1:node:agent-node",
        AgentExecutionStatus.FAILED
    );
    Instant completedAt = Instant.parse("2026-08-08T04:00:00Z");
    failed.setCompletedAt(completedAt);
    failed.setErrorCode("MODEL_GATEWAY_UNAVAILABLE");
    failed.setErrorMessage("upstream failure");
    when(agentService.cancelPinnedChild(any())).thenReturn(failed);
    when(agentRepository.findActiveById("agent-execution-1"))
        .thenReturn(Optional.of(failed));

    coordinator.terminate(
        execution,
        List.of(node),
        "workflow-runtime",
        "workflow failed",
        Instant.parse("2026-08-08T05:00:00Z")
    );

    assertThat(node.getStatus())
        .isEqualTo(WorkflowNodeExecutionStatus.FAILED);
    assertThat(node.getErrorCode())
        .isEqualTo("WORKFLOW_AGENT_CHILD_FAILED");
    assertThat(node.getErrorMessage())
        .isEqualTo("WORKFLOW_AGENT_CHILD_FAILED");
    assertThat(node.getCompletedAt()).isEqualTo(completedAt);
    verify(eventPublisher).publish(
        eq(execution),
        eq(WorkflowExecutionEventType.NODE_FAILED),
        eq(node.getId()),
        any(),
        eq("workflow-runtime"),
        any(),
        any()
    );
  }

  @Test
  void rejectsCorruptionBeforeIssuingAnyChildCommand() {
    final AiWorkflowExecution execution = execution();
    AiWorkflowNodeExecution valid = childNode(
        0, "agent-node", "agent", "AGENT",
        "agent-1", "version-1", "agent-execution-1"
    );
    AiWorkflowNodeExecution corrupt = node(
        1, "skill-node", "skill", WorkflowNodeExecutionStatus.WAITING
    );
    corrupt.setChildType("SKILL");
    corrupt.setChildResourceId("skill-1");

    assertThatThrownBy(() -> coordinator.terminate(
        execution,
        List.of(valid, corrupt),
        "workflow-runtime",
        "workflow failed",
        Instant.now()
    )).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid child version ID");

    verify(agentService, never()).cancelPinnedChild(any());
    verify(skillService, never()).cancelPinnedChild(any());
    verify(nodeRepository, never()).save(any());
  }

  @Test
  void rejectsMismatchedChildReturnedByCancellationBoundary() {
    final AiWorkflowExecution execution = execution();
    final AiWorkflowNodeExecution node = childNode(
        0, "skill-node", "skill", "SKILL",
        "skill-1", "version-1", "skill-execution-1"
    );
    AiSkillExecution wrongTenant = skillChild(
        "skill-1",
        "version-1",
        "skill-execution-1",
        "execution-1:node:skill-node",
        SkillExecutionStatus.CANCELLED
    );
    wrongTenant.setTenantId("another-tenant");
    AiSkillExecution stored = skillChild(
        "skill-1",
        "version-1",
        "skill-execution-1",
        "execution-1:node:skill-node",
        SkillExecutionStatus.RUNNING
    );
    when(skillRepository.findActiveById("skill-execution-1"))
        .thenReturn(Optional.of(stored));
    when(skillService.cancelPinnedChild(any())).thenReturn(wrongTenant);

    assertThatThrownBy(() -> coordinator.terminate(
        execution,
        List.of(node),
        "workflow-runtime",
        "workflow failed",
        Instant.now()
    )).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Skill child association is corrupted");

    assertThat(node.getStatus()).isEqualTo(WorkflowNodeExecutionStatus.WAITING);
    verify(nodeRepository, never()).save(any());
    verify(eventPublisher, never()).publish(
        any(), any(), any(), any(), any(), any(), any()
    );
  }

  @Test
  void waitsForNestedAgentSkillRealityEvenAfterAgentIsTerminal() {
    final AiWorkflowExecution execution = execution();
    final AiWorkflowNodeExecution node = childNode(
        0, "agent-node", "agent", "AGENT",
        "agent-1", "version-1", "agent-execution-1"
    );
    node.setStatus(WorkflowNodeExecutionStatus.CANCELLED);
    AiAgentExecution agent = agentChild(
        "agent-1",
        "version-1",
        "agent-execution-1",
        "execution-1:node:agent-node",
        AgentExecutionStatus.CANCELLED
    );
    when(agentRepository.findActiveById("agent-execution-1"))
        .thenReturn(Optional.of(agent));
    AiAgentExecutionTrace trace = new AiAgentExecutionTrace();
    trace.setId("trace-1");
    trace.setExecutionId("agent-execution-1");
    trace.setType(AgentTraceType.SKILL);
    trace.setStatus(AgentTraceStatus.CANCELLED);
    trace.setSkillId("nested-skill");
    trace.setSkillVersionId("nested-version");
    trace.setSkillExecutionId("nested-execution");
    trace.setToolCallId("tool-call-1");
    when(traceRepository.findAllActiveByExecutionId("agent-execution-1"))
        .thenReturn(List.of(trace));
    AiSkillExecution nested = skillChild(
        "nested-skill",
        "nested-version",
        "nested-execution",
        "agent-execution-1:tool-call-1",
        SkillExecutionStatus.RUNNING
    );
    when(skillRepository.findActiveById("nested-execution"))
        .thenReturn(Optional.of(nested));

    assertThat(coordinator.forwardChildrenSettled(
        execution, List.of(node)
    )).isFalse();

    nested.setStatus(SkillExecutionStatus.SUCCEEDED);
    assertThat(coordinator.forwardChildrenSettled(
        execution, List.of(node)
    )).isTrue();
  }

  @Test
  void waitsForDirectSkillRealityAfterNodeWasCancelled() {
    AiWorkflowExecution execution = execution();
    AiWorkflowNodeExecution node = childNode(
        0, "skill-node", "skill", "SKILL",
        "skill-1", "version-1", "skill-execution-1"
    );
    node.setStatus(WorkflowNodeExecutionStatus.CANCELLED);
    AiSkillExecution skill = skillChild(
        "skill-1",
        "version-1",
        "skill-execution-1",
        "execution-1:node:skill-node",
        SkillExecutionStatus.RUNNING
    );
    when(skillRepository.findActiveById("skill-execution-1"))
        .thenReturn(Optional.of(skill));

    assertThat(coordinator.forwardChildrenSettled(
        execution, List.of(node)
    )).isFalse();
    skill.setStatus(SkillExecutionStatus.CANCELLED);
    assertThat(coordinator.forwardChildrenSettled(
        execution, List.of(node)
    )).isTrue();
  }

  @Test
  void requiresOneRollbackCapableCallerTransaction() throws Exception {
    Transactional terminate = AiWorkflowTerminationCoordinator.class
        .getMethod(
            "terminate",
            AiWorkflowExecution.class,
            java.util.Collection.class,
            String.class,
            String.class,
            Instant.class
        ).getAnnotation(Transactional.class);
    Transactional settled = AiWorkflowTerminationCoordinator.class
        .getMethod(
            "forwardChildrenSettled",
            AiWorkflowExecution.class,
            java.util.Collection.class
        ).getAnnotation(Transactional.class);

    assertThat(terminate.propagation()).isEqualTo(Propagation.MANDATORY);
    assertThat(terminate.rollbackFor()).contains(Exception.class);
    assertThat(settled.propagation()).isEqualTo(Propagation.MANDATORY);
    assertThat(settled.rollbackFor()).contains(Exception.class);
  }

  private static AiWorkflowExecution execution() {
    AiWorkflowExecution execution = new AiWorkflowExecution();
    execution.setId("execution-1");
    execution.setScopeType(AiResourceScope.TENANT);
    execution.setTenantId("tenant-1");
    execution.setStatus(WorkflowExecutionStatus.CANCELLED);
    return execution;
  }

  private static AiWorkflowNodeExecution node(
      final int order,
      final String nodeId,
      final String type,
      final WorkflowNodeExecutionStatus status
  ) {
    AiWorkflowNodeExecution node = new AiWorkflowNodeExecution();
    node.setId("node-execution-" + order);
    node.setExecutionId("execution-1");
    node.setNodeId(nodeId);
    node.setNodeType(type);
    node.setNodeOrder(order);
    node.setStatus(status);
    node.setAttemptCount(1);
    return node;
  }

  private static AiWorkflowNodeExecution childNode(
      final int order,
      final String nodeId,
      final String nodeType,
      final String childType,
      final String resourceId,
      final String versionId,
      final String executionId
  ) {
    AiWorkflowNodeExecution node = node(
        order, nodeId, nodeType, WorkflowNodeExecutionStatus.WAITING
    );
    node.setChildType(childType);
    node.setChildResourceId(resourceId);
    node.setChildResourceVersionId(versionId);
    node.setChildExecutionId(executionId);
    return node;
  }

  private static AiWorkflowHumanTask humanTask(
      final AiWorkflowNodeExecution node
  ) {
    AiWorkflowHumanTask task = new AiWorkflowHumanTask();
    task.setId("task-1");
    task.setExecutionId("execution-1");
    task.setNodeExecutionId(node.getId());
    task.setNodeId(node.getNodeId());
    task.setScopeType(AiResourceScope.TENANT);
    task.setTenantId("tenant-1");
    task.setStatus(WorkflowHumanTaskStatus.OPEN);
    return task;
  }

  private static AiAgentExecution agentChild(
      final String agentId,
      final String versionId,
      final String executionId,
      final String stableKey,
      final AgentExecutionStatus status
  ) {
    AiAgentExecution child = new AiAgentExecution();
    child.setId(executionId);
    child.setAgentId(agentId);
    child.setAgentVersionId(versionId);
    child.setScopeType(AiResourceScope.TENANT);
    child.setTenantId("tenant-1");
    child.setIdempotencyKeyHash(sha256(stableKey));
    child.setStatus(status);
    return child;
  }

  private static AiSkillExecution skillChild(
      final String skillId,
      final String versionId,
      final String executionId,
      final String stableKey,
      final SkillExecutionStatus status
  ) {
    AiSkillExecution child = new AiSkillExecution();
    child.setId(executionId);
    child.setSkillId(skillId);
    child.setSkillVersionId(versionId);
    child.setSourceType(SkillExecutionSource.PUBLISHED);
    child.setScopeType(AiResourceScope.TENANT);
    child.setTenantId("tenant-1");
    child.setIdempotencyKeyHash(sha256(stableKey));
    child.setStatus(status);
    return child;
  }

  private static String sha256(final String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(
              value.getBytes(StandardCharsets.UTF_8)
          )
      );
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
