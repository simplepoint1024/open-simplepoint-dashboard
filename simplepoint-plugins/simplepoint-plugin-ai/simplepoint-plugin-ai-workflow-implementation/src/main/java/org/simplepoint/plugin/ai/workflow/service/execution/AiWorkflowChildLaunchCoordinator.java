package org.simplepoint.plugin.ai.workflow.service.execution;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecution;
import org.simplepoint.plugin.ai.agent.api.model.AgentWorkflowExecutionCommand;
import org.simplepoint.plugin.ai.agent.api.service.AiAgentExecutionService;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecution;
import org.simplepoint.plugin.ai.skill.api.model.SkillWorkflowExecutionCommand;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillExecutionService;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowNodeExecution;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowNodeExecutionStatus;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowNodeExecutionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomically creates a Workflow child execution and associates its node.
 *
 * <p>The Workflow engine owns a longer transaction while advancing an
 * execution. Child creation must commit independently so workers can observe
 * it, but it must never commit without its durable node association. Each
 * method therefore owns one isolated launch transaction and requires child
 * services to participate in it.</p>
 */
@Service
public class AiWorkflowChildLaunchCoordinator {

  private final AiWorkflowNodeExecutionRepository nodeRepository;

  private final AiAgentExecutionService agentExecutionService;

  private final AiSkillExecutionService skillExecutionService;

  /**
   * Creates the atomic child launch coordinator.
   */
  public AiWorkflowChildLaunchCoordinator(
      final AiWorkflowNodeExecutionRepository nodeRepository,
      final AiAgentExecutionService agentExecutionService,
      final AiSkillExecutionService skillExecutionService
  ) {
    this.nodeRepository = nodeRepository;
    this.agentExecutionService = agentExecutionService;
    this.skillExecutionService = skillExecutionService;
  }

  /**
   * Creates or reuses the Agent child and durably associates the node.
   */
  @Transactional(
      propagation = Propagation.REQUIRES_NEW,
      rollbackFor = Exception.class
  )
  public NodeLaunchResult launchAgent(
      final String executionId,
      final String nodeId,
      final String inputJson,
      final Instant startedAt,
      final AgentWorkflowExecutionCommand command
  ) {
    if (command == null) {
      throw new IllegalArgumentException(
          "Workflow Agent execution command is required"
      );
    }
    AiWorkflowNodeExecution node = lockNode(executionId, nodeId);
    assertNodeType(node, "agent");
    assertIdempotencyKey(
        command.idempotencyKey(),
        executionId + ":node:" + nodeId
    );
    Optional<NodeLaunchResult> existing = existingNormalLaunch(
        node,
        "AGENT",
        command.agentId(),
        command.agentVersionId(),
        inputJson
    );
    if (existing.isPresent()) {
      return existing.orElseThrow();
    }
    assertPending(node);

    AiAgentExecution child = agentExecutionService
        .startVersionForWorkflow(command);
    assertAgentChild(child, command);
    associateNormalChild(
        node,
        "AGENT",
        command.agentId(),
        command.agentVersionId(),
        child.getId(),
        inputJson,
        startedAt
    );
    return new NodeLaunchResult(nodeRepository.save(node), true);
  }

  /**
   * Creates or reuses the Skill child and durably associates the node.
   */
  @Transactional(
      propagation = Propagation.REQUIRES_NEW,
      rollbackFor = Exception.class
  )
  public NodeLaunchResult launchSkill(
      final String executionId,
      final String nodeId,
      final String inputJson,
      final Instant startedAt,
      final SkillWorkflowExecutionCommand command
  ) {
    if (command == null) {
      throw new IllegalArgumentException(
          "Workflow Skill execution command is required"
      );
    }
    AiWorkflowNodeExecution node = lockNode(executionId, nodeId);
    assertNodeType(node, "skill");
    assertIdempotencyKey(
        command.idempotencyKey(),
        executionId + ":node:" + nodeId
    );
    Optional<NodeLaunchResult> existing = existingNormalLaunch(
        node,
        "SKILL",
        command.skillId(),
        command.skillVersionId(),
        inputJson
    );
    if (existing.isPresent()) {
      return existing.orElseThrow();
    }
    assertPending(node);

    AiSkillExecution child = skillExecutionService
        .startVersionForWorkflow(command);
    assertSkillChild(child, command);
    associateNormalChild(
        node,
        "SKILL",
        command.skillId(),
        command.skillVersionId(),
        child.getId(),
        inputJson,
        startedAt
    );
    return new NodeLaunchResult(nodeRepository.save(node), true);
  }

  /**
   * Creates or reuses a compensation Skill and associates the node.
   */
  @Transactional(
      propagation = Propagation.REQUIRES_NEW,
      rollbackFor = Exception.class
  )
  public NodeLaunchResult launchCompensation(
      final String executionId,
      final String nodeId,
      final SkillWorkflowExecutionCommand command
  ) {
    if (command == null) {
      throw new IllegalArgumentException(
          "Workflow compensation execution command is required"
      );
    }
    AiWorkflowNodeExecution node = lockNode(executionId, nodeId);
    assertIdempotencyKey(
        command.idempotencyKey(),
        executionId + ":compensate:" + nodeId
    );
    assertCompensationBinding(node, command);
    if (node.getCompensationExecutionId() != null) {
      if (node.getStatus() != WorkflowNodeExecutionStatus.COMPENSATING) {
        throw conflict(node, "has an invalid compensation association");
      }
      return new NodeLaunchResult(node, false);
    }
    if (node.getStatus() != WorkflowNodeExecutionStatus.SUCCEEDED) {
      throw conflict(node, "is not ready for compensation");
    }

    AiSkillExecution child = skillExecutionService
        .startVersionForWorkflow(command);
    assertSkillChild(child, command);
    node.setStatus(WorkflowNodeExecutionStatus.COMPENSATING);
    node.setCompensationExecutionId(child.getId());
    node.setErrorCode(null);
    node.setErrorMessage(null);
    return new NodeLaunchResult(nodeRepository.save(node), true);
  }

  private AiWorkflowNodeExecution lockNode(
      final String executionId,
      final String nodeId
  ) {
    if (executionId == null || executionId.isBlank()
        || nodeId == null || nodeId.isBlank()) {
      throw new IllegalArgumentException(
          "Workflow execution ID and node ID are required"
      );
    }
    return nodeRepository.findActiveByExecutionIdAndNodeIdForUpdate(
        executionId,
        nodeId
    ).orElseThrow(() -> new IllegalStateException(
        "Workflow node checkpoint disappeared"
    ));
  }

  private static void assertNodeType(
      final AiWorkflowNodeExecution node,
      final String expected
  ) {
    if (!expected.equalsIgnoreCase(node.getNodeType())) {
      throw conflict(node, "has an incompatible child type");
    }
  }

  private static void assertPending(
      final AiWorkflowNodeExecution node
  ) {
    if (node.getStatus() != WorkflowNodeExecutionStatus.PENDING
        || node.getCompensationExecutionId() != null) {
      throw conflict(node, "is not pending child launch");
    }
  }

  private static Optional<NodeLaunchResult> existingNormalLaunch(
      final AiWorkflowNodeExecution node,
      final String childType,
      final String resourceId,
      final String resourceVersionId,
      final String inputJson
  ) {
    boolean associated = node.getChildType() != null
        || node.getChildResourceId() != null
        || node.getChildResourceVersionId() != null
        || node.getChildExecutionId() != null;
    if (!associated) {
      return Optional.empty();
    }
    if (node.getStatus() != WorkflowNodeExecutionStatus.WAITING
        || node.getChildExecutionId() == null
        || node.getChildExecutionId().isBlank()
        || node.getAttemptCount() == null
        || node.getAttemptCount() < 1
        || node.getStartedAt() == null
        || !Objects.equals(childType, node.getChildType())
        || !Objects.equals(resourceId, node.getChildResourceId())
        || !Objects.equals(
            resourceVersionId,
            node.getChildResourceVersionId()
        )
        || !Objects.equals(inputJson, node.getInputJson())) {
      throw conflict(node, "has a conflicting child association");
    }
    return Optional.of(new NodeLaunchResult(node, false));
  }

  private static void associateNormalChild(
      final AiWorkflowNodeExecution node,
      final String childType,
      final String resourceId,
      final String resourceVersionId,
      final String childExecutionId,
      final String inputJson,
      final Instant startedAt
  ) {
    if (inputJson == null || inputJson.isBlank() || startedAt == null) {
      throw new IllegalArgumentException(
          "Workflow child input and start time are required"
      );
    }
    node.setInputJson(inputJson);
    node.setAttemptCount(
        (node.getAttemptCount() == null ? 0 : node.getAttemptCount()) + 1
    );
    node.setStartedAt(startedAt);
    node.setCompletedAt(null);
    node.setScheduledAt(null);
    node.setErrorCode(null);
    node.setErrorMessage(null);
    node.setChildType(childType);
    node.setChildResourceId(resourceId);
    node.setChildResourceVersionId(resourceVersionId);
    node.setChildExecutionId(childExecutionId);
    node.setStatus(WorkflowNodeExecutionStatus.WAITING);
  }

  private static void assertCompensationBinding(
      final AiWorkflowNodeExecution node,
      final SkillWorkflowExecutionCommand command
  ) {
    if (command == null
        || !Objects.equals(
            node.getCompensationSkillId(),
            command.skillId()
        )
        || !Objects.equals(
            node.getCompensationSkillVersionId(),
            command.skillVersionId()
        )
        || !Objects.equals(
            node.getCompensationContentHash(),
            command.expectedContentHash()
        )) {
      throw conflict(node, "has a conflicting compensation binding");
    }
  }

  private static void assertIdempotencyKey(
      final String actual,
      final String expected
  ) {
    if (!Objects.equals(expected, actual)) {
      throw new IllegalArgumentException(
          "Workflow child idempotency key does not match its node"
      );
    }
  }

  private static void assertAgentChild(
      final AiAgentExecution child,
      final AgentWorkflowExecutionCommand command
  ) {
    if (child == null || child.getId() == null || child.getId().isBlank()
        || !Objects.equals(command.agentId(), child.getAgentId())
        || !Objects.equals(
            command.agentVersionId(),
            child.getAgentVersionId()
        )
        || !Objects.equals(
            command.expectedContentHash(),
            child.getAgentVersionContentHash()
        )
        || !Objects.equals(command.executionScope(), child.getScopeType())
        || !Objects.equals(command.tenantId(), child.getTenantId())) {
      throw new IllegalStateException(
          "Workflow Agent child does not match its pinned node"
      );
    }
  }

  private static void assertSkillChild(
      final AiSkillExecution child,
      final SkillWorkflowExecutionCommand command
  ) {
    if (child == null || child.getId() == null || child.getId().isBlank()
        || !Objects.equals(command.skillId(), child.getSkillId())
        || !Objects.equals(
            command.skillVersionId(),
            child.getSkillVersionId()
        )
        || !Objects.equals(command.executionScope(), child.getScopeType())
        || !Objects.equals(command.tenantId(), child.getTenantId())) {
      throw new IllegalStateException(
          "Workflow Skill child does not match its pinned node"
      );
    }
  }

  private static IllegalStateException conflict(
      final AiWorkflowNodeExecution node,
      final String reason
  ) {
    return new IllegalStateException(
        "Workflow node " + node.getNodeId() + " " + reason
    );
  }

  /**
   * Persisted launch checkpoint returned to the suspended outer transaction.
   *
   * @param node atomically associated node checkpoint
   * @param created whether this call created the child association
   */
  public record NodeLaunchResult(
      AiWorkflowNodeExecution node,
      boolean created
  ) {
  }
}
