package org.simplepoint.plugin.ai.workflow.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecution;
import org.simplepoint.plugin.ai.agent.api.model.AgentWorkflowExecutionCommand;
import org.simplepoint.plugin.ai.agent.api.service.AiAgentExecutionService;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecution;
import org.simplepoint.plugin.ai.skill.api.model.SkillWorkflowExecutionCommand;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillExecutionService;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowNodeExecution;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowNodeExecutionStatus;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowNodeExecutionRepository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class AiWorkflowChildLaunchCoordinatorTest {

  private static final String CONTENT_HASH = "a".repeat(64);

  private AiWorkflowNodeExecutionRepository nodeRepository;

  private AiAgentExecutionService agentExecutionService;

  private AiSkillExecutionService skillExecutionService;

  private AiWorkflowChildLaunchCoordinator coordinator;

  @BeforeEach
  void setUp() {
    nodeRepository = mock(AiWorkflowNodeExecutionRepository.class);
    agentExecutionService = mock(AiAgentExecutionService.class);
    skillExecutionService = mock(AiSkillExecutionService.class);
    when(nodeRepository.save(any(AiWorkflowNodeExecution.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    coordinator = new AiWorkflowChildLaunchCoordinator(
        nodeRepository,
        agentExecutionService,
        skillExecutionService
    );
  }

  @Test
  void launchMethodsOwnRequiresNewTransactions() throws Exception {
    Method agent = AiWorkflowChildLaunchCoordinator.class.getMethod(
        "launchAgent",
        String.class,
        String.class,
        String.class,
        Instant.class,
        AgentWorkflowExecutionCommand.class
    );
    Method skill = AiWorkflowChildLaunchCoordinator.class.getMethod(
        "launchSkill",
        String.class,
        String.class,
        String.class,
        Instant.class,
        SkillWorkflowExecutionCommand.class
    );
    Method compensation = AiWorkflowChildLaunchCoordinator.class.getMethod(
        "launchCompensation",
        String.class,
        String.class,
        SkillWorkflowExecutionCommand.class
    );

    assertRequiresNew(agent);
    assertRequiresNew(skill);
    assertRequiresNew(compensation);
  }

  @Test
  void atomicallyAssociatesAgentChild() {
    AiWorkflowNodeExecution node = node("agent");
    final AgentWorkflowExecutionCommand command = agentCommand();
    AiAgentExecution child = agentChild("agent-execution-1");
    when(nodeRepository.findActiveByExecutionIdAndNodeIdForUpdate(
        "workflow-execution-1",
        "node-1"
    )).thenReturn(Optional.of(node));
    when(agentExecutionService.startVersionForWorkflow(command))
        .thenReturn(child);
    Instant startedAt = Instant.parse("2026-08-08T01:02:03Z");

    AiWorkflowChildLaunchCoordinator.NodeLaunchResult result =
        coordinator.launchAgent(
            "workflow-execution-1",
            "node-1",
            "{\"prompt\":\"hello\"}",
            startedAt,
            command
        );

    assertThat(result.created()).isTrue();
    assertThat(result.node()).isSameAs(node);
    assertThat(node.getStatus())
        .isEqualTo(WorkflowNodeExecutionStatus.WAITING);
    assertThat(node.getInputJson()).isEqualTo("{\"prompt\":\"hello\"}");
    assertThat(node.getAttemptCount()).isEqualTo(1);
    assertThat(node.getStartedAt()).isEqualTo(startedAt);
    assertThat(node.getChildType()).isEqualTo("AGENT");
    assertThat(node.getChildResourceId()).isEqualTo("agent-1");
    assertThat(node.getChildResourceVersionId()).isEqualTo("agent-version-1");
    assertThat(node.getChildExecutionId()).isEqualTo("agent-execution-1");
    verify(nodeRepository).save(node);
  }

  @Test
  void reusesMatchingAgentAssociationWithoutCreatingDuplicate() {
    AiWorkflowNodeExecution node = node("agent");
    AgentWorkflowExecutionCommand command = agentCommand();
    when(nodeRepository.findActiveByExecutionIdAndNodeIdForUpdate(
        "workflow-execution-1",
        "node-1"
    )).thenReturn(Optional.of(node));
    when(agentExecutionService.startVersionForWorkflow(command))
        .thenReturn(agentChild("agent-execution-1"));
    Instant startedAt = Instant.parse("2026-08-08T01:02:03Z");

    coordinator.launchAgent(
        "workflow-execution-1",
        "node-1",
        "{}",
        startedAt,
        command
    );
    AiWorkflowChildLaunchCoordinator.NodeLaunchResult replay =
        coordinator.launchAgent(
            "workflow-execution-1",
            "node-1",
            "{}",
            startedAt,
            command
        );

    assertThat(replay.created()).isFalse();
    assertThat(replay.node().getChildExecutionId())
        .isEqualTo("agent-execution-1");
    verify(agentExecutionService).startVersionForWorkflow(command);
    verify(nodeRepository).save(node);
  }

  @Test
  void rejectsConflictingExistingAssociation() {
    AiWorkflowNodeExecution node = node("agent");
    node.setStatus(WorkflowNodeExecutionStatus.WAITING);
    node.setAttemptCount(1);
    node.setStartedAt(Instant.now());
    node.setInputJson("{}");
    node.setChildType("AGENT");
    node.setChildResourceId("another-agent");
    node.setChildResourceVersionId("agent-version-1");
    node.setChildExecutionId("agent-execution-existing");
    when(nodeRepository.findActiveByExecutionIdAndNodeIdForUpdate(
        "workflow-execution-1",
        "node-1"
    )).thenReturn(Optional.of(node));

    assertThatThrownBy(() -> coordinator.launchAgent(
        "workflow-execution-1",
        "node-1",
        "{}",
        Instant.now(),
        agentCommand()
    )).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicting child association");

    verify(agentExecutionService, never())
        .startVersionForWorkflow(any());
    verify(nodeRepository, never()).save(any());
  }

  @Test
  void childFailureLeavesNodeUnassociatedForTransactionRollback() {
    AiWorkflowNodeExecution node = node("skill");
    SkillWorkflowExecutionCommand command = skillCommand(
        "workflow-execution-1:node:node-1"
    );
    when(nodeRepository.findActiveByExecutionIdAndNodeIdForUpdate(
        "workflow-execution-1",
        "node-1"
    )).thenReturn(Optional.of(node));
    when(skillExecutionService.startVersionForWorkflow(command))
        .thenThrow(new IllegalStateException("child rejected"));

    assertThatThrownBy(() -> coordinator.launchSkill(
        "workflow-execution-1",
        "node-1",
        "{}",
        Instant.now(),
        command
    )).isInstanceOf(IllegalStateException.class)
        .hasMessage("child rejected");

    assertThat(node.getStatus())
        .isEqualTo(WorkflowNodeExecutionStatus.PENDING);
    assertThat(node.getChildExecutionId()).isNull();
    verify(nodeRepository, never()).save(any());
  }

  @Test
  void rejectsAgentChildFromDifferentScopeOrContent() {
    AiWorkflowNodeExecution node = node("agent");
    final AgentWorkflowExecutionCommand command = agentCommand();
    AiAgentExecution child = agentChild("agent-execution-1");
    child.setTenantId("another-tenant");
    child.setAgentVersionContentHash("b".repeat(64));
    when(nodeRepository.findActiveByExecutionIdAndNodeIdForUpdate(
        "workflow-execution-1",
        "node-1"
    )).thenReturn(Optional.of(node));
    when(agentExecutionService.startVersionForWorkflow(command))
        .thenReturn(child);

    assertThatThrownBy(() -> coordinator.launchAgent(
        "workflow-execution-1",
        "node-1",
        "{}",
        Instant.now(),
        command
    )).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not match its pinned node");

    assertThat(node.getChildExecutionId()).isNull();
    verify(nodeRepository, never()).save(any());
  }

  @Test
  void atomicallyAssociatesSkillAndCompensationChildren() {
    AiWorkflowNodeExecution skillNode = node("skill");
    SkillWorkflowExecutionCommand skillCommand = skillCommand(
        "workflow-execution-1:node:node-1"
    );
    when(nodeRepository.findActiveByExecutionIdAndNodeIdForUpdate(
        "workflow-execution-1",
        "node-1"
    )).thenReturn(Optional.of(skillNode));
    when(skillExecutionService.startVersionForWorkflow(skillCommand))
        .thenReturn(skillChild("skill-execution-1"));

    coordinator.launchSkill(
        "workflow-execution-1",
        "node-1",
        "{}",
        Instant.parse("2026-08-08T01:02:03Z"),
        skillCommand
    );

    assertThat(skillNode.getStatus())
        .isEqualTo(WorkflowNodeExecutionStatus.WAITING);
    assertThat(skillNode.getChildType()).isEqualTo("SKILL");
    assertThat(skillNode.getChildExecutionId())
        .isEqualTo("skill-execution-1");

    AiWorkflowNodeExecution compensationNode = node("agent");
    compensationNode.setStatus(WorkflowNodeExecutionStatus.SUCCEEDED);
    compensationNode.setCompensationSkillId("skill-1");
    compensationNode.setCompensationSkillVersionId("skill-version-1");
    compensationNode.setCompensationContentHash(CONTENT_HASH);
    SkillWorkflowExecutionCommand compensationCommand = skillCommand(
        "workflow-execution-1:compensate:node-1"
    );
    when(nodeRepository.findActiveByExecutionIdAndNodeIdForUpdate(
        "workflow-execution-1",
        "node-1"
    )).thenReturn(Optional.of(compensationNode));
    when(skillExecutionService.startVersionForWorkflow(compensationCommand))
        .thenReturn(skillChild("compensation-execution-1"));

    coordinator.launchCompensation(
        "workflow-execution-1",
        "node-1",
        compensationCommand
    );

    assertThat(compensationNode.getStatus())
        .isEqualTo(WorkflowNodeExecutionStatus.COMPENSATING);
    assertThat(compensationNode.getCompensationExecutionId())
        .isEqualTo("compensation-execution-1");
    verify(skillExecutionService, times(2)).startVersionForWorkflow(any());
  }

  @Test
  void rejectsSkillChildFromDifferentScope() {
    AiWorkflowNodeExecution node = node("skill");
    SkillWorkflowExecutionCommand command = skillCommand(
        "workflow-execution-1:node:node-1"
    );
    AiSkillExecution child = skillChild("skill-execution-1");
    child.setScopeType(AiResourceScope.SYSTEM);
    when(nodeRepository.findActiveByExecutionIdAndNodeIdForUpdate(
        "workflow-execution-1",
        "node-1"
    )).thenReturn(Optional.of(node));
    when(skillExecutionService.startVersionForWorkflow(command))
        .thenReturn(child);

    assertThatThrownBy(() -> coordinator.launchSkill(
        "workflow-execution-1",
        "node-1",
        "{}",
        Instant.now(),
        command
    )).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not match its pinned node");

    verify(nodeRepository, never()).save(any());
  }

  private static void assertRequiresNew(final Method method) {
    Transactional annotation = method.getAnnotation(Transactional.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.propagation())
        .isEqualTo(Propagation.REQUIRES_NEW);
    assertThat(annotation.rollbackFor()).contains(Exception.class);
  }

  private static AiWorkflowNodeExecution node(final String type) {
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

  private static AgentWorkflowExecutionCommand agentCommand() {
    return new AgentWorkflowExecutionCommand(
        "agent-1",
        "agent-version-1",
        CONTENT_HASH,
        AiResourceScope.TENANT,
        "tenant-1",
        "user-1",
        "context-1",
        "workflow-execution-1:node:node-1",
        Map.of("prompt", "hello")
    );
  }

  private static SkillWorkflowExecutionCommand skillCommand(
      final String idempotencyKey
  ) {
    return new SkillWorkflowExecutionCommand(
        "skill-1",
        "skill-version-1",
        CONTENT_HASH,
        AiResourceScope.TENANT,
        "tenant-1",
        "user-1",
        idempotencyKey,
        Map.of("prompt", "hello")
    );
  }

  private static AiAgentExecution agentChild(final String id) {
    AiAgentExecution child = new AiAgentExecution();
    child.setId(id);
    child.setAgentId("agent-1");
    child.setAgentVersionId("agent-version-1");
    child.setAgentVersionContentHash(CONTENT_HASH);
    child.setScopeType(AiResourceScope.TENANT);
    child.setTenantId("tenant-1");
    return child;
  }

  private static AiSkillExecution skillChild(final String id) {
    AiSkillExecution child = new AiSkillExecution();
    child.setId(id);
    child.setSkillId("skill-1");
    child.setSkillVersionId("skill-version-1");
    child.setScopeType(AiResourceScope.TENANT);
    child.setTenantId("tenant-1");
    return child;
  }
}
