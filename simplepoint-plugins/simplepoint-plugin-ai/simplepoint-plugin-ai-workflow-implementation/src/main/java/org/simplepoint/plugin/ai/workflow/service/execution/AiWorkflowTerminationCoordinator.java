package org.simplepoint.plugin.ai.workflow.service.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecution;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecutionTrace;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentPinnedChildCancelCommand;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceType;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentExecutionRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentExecutionTraceRepository;
import org.simplepoint.plugin.ai.agent.api.service.AiAgentExecutionService;
import org.simplepoint.plugin.ai.core.service.support.AiExecutionDiagnostics;
import org.simplepoint.plugin.ai.core.service.support.AiExecutionDiagnostics.StableDiagnostic;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction-bound, deterministic termination of unfinished Workflow work.
 *
 * <p>The caller must hold the owning Workflow execution lock. Child services
 * participate in that same transaction, so a corrupt association or failed
 * cancellation rolls the complete termination back. Nodes are always handled
 * in persisted {@code node_order}; this is also the cross-runtime lock order.</p>
 */
@Service
public class AiWorkflowTerminationCoordinator {

  private static final Set<WorkflowNodeExecutionStatus> NODE_TERMINAL =
      Set.of(
          WorkflowNodeExecutionStatus.SUCCEEDED,
          WorkflowNodeExecutionStatus.FAILED,
          WorkflowNodeExecutionStatus.CANCELLED,
          WorkflowNodeExecutionStatus.SKIPPED,
          WorkflowNodeExecutionStatus.COMPENSATED,
          WorkflowNodeExecutionStatus.COMPENSATION_FAILED
      );

  private final AiWorkflowNodeExecutionRepository nodeRepository;

  private final AiWorkflowHumanTaskRepository taskRepository;

  private final AiAgentExecutionRepository agentExecutionRepository;

  private final AiAgentExecutionTraceRepository agentTraceRepository;

  private final AiSkillExecutionRepository skillExecutionRepository;

  private final AiAgentExecutionService agentExecutionService;

  private final AiSkillExecutionService skillExecutionService;

  private final AiWorkflowExecutionEventPublisher eventPublisher;

  /** Creates the transaction-bound Workflow termination coordinator. */
  public AiWorkflowTerminationCoordinator(
      final AiWorkflowNodeExecutionRepository nodeRepository,
      final AiWorkflowHumanTaskRepository taskRepository,
      final AiAgentExecutionRepository agentExecutionRepository,
      final AiAgentExecutionTraceRepository agentTraceRepository,
      final AiSkillExecutionRepository skillExecutionRepository,
      final AiAgentExecutionService agentExecutionService,
      final AiSkillExecutionService skillExecutionService,
      final AiWorkflowExecutionEventPublisher eventPublisher
  ) {
    this.nodeRepository = nodeRepository;
    this.taskRepository = taskRepository;
    this.agentExecutionRepository = agentExecutionRepository;
    this.agentTraceRepository = agentTraceRepository;
    this.skillExecutionRepository = skillExecutionRepository;
    this.agentExecutionService = agentExecutionService;
    this.skillExecutionService = skillExecutionService;
    this.eventPublisher = eventPublisher;
  }

  /**
   * Cancels every unfinished normal or compensation child and closes waits.
   *
   * <p>Already terminal node and task facts are deliberately not rewritten.
   * A child that wins the race to success or failure is mapped to that exact
   * terminal result before the parent cancellation is finalized.</p>
   */
  @Transactional(
      propagation = Propagation.MANDATORY,
      rollbackFor = Exception.class
  )
  public void terminate(
      final AiWorkflowExecution execution,
      final Collection<AiWorkflowNodeExecution> checkpoints,
      final String actorId,
      final String reason,
      final Instant terminatedAt
  ) {
    final AiWorkflowExecution owner = requireExecution(execution);
    final String actor = required(actorId, "Workflow termination actor", 64);
    required(
        reason,
        "Workflow termination reason",
        1024
    );
    final String cancellationReason = terminationReason(owner);
    final Instant now = terminatedAt == null ? Instant.now() : terminatedAt;
    List<AiWorkflowNodeExecution> nodes = orderedNodes(owner, checkpoints);
    Map<String, AiWorkflowNodeExecution> nodesByExecutionId =
        new HashMap<>();
    Map<String, NormalBinding> normalBindings = new HashMap<>();
    Map<String, CompensationBinding> compensationBindings = new HashMap<>();
    for (AiWorkflowNodeExecution node : nodes) {
      NormalBinding normal = normalBinding(node);
      CompensationBinding compensation = compensationBinding(node);
      if (normal != null) {
        normalBindings.put(node.getId(), normal);
      }
      if (compensation != null) {
        compensationBindings.put(node.getId(), compensation);
      }
      nodesByExecutionId.put(node.getId(), node);
    }
    List<AiWorkflowHumanTask> tasks = new ArrayList<>(
        taskRepository.findAllActiveByExecutionId(owner.getId())
    );
    validateTasks(owner, nodesByExecutionId, tasks);
    for (AiWorkflowNodeExecution node : nodes) {
      validateStoredChildren(
          owner,
          node,
          normalBindings.get(node.getId()),
          compensationBindings.get(node.getId())
      );
    }

    for (AiWorkflowNodeExecution node : nodes) {
      terminateNode(owner, node, actor, cancellationReason, now);
    }
    tasks.sort(Comparator
        .comparing((AiWorkflowHumanTask task) ->
            nodesByExecutionId.get(task.getNodeExecutionId()).getNodeOrder())
        .thenComparing(AiWorkflowHumanTask::getId));
    for (AiWorkflowHumanTask task : tasks) {
      if (task.getStatus() != WorkflowHumanTaskStatus.OPEN) {
        continue;
      }
      task.setStatus(WorkflowHumanTaskStatus.CANCELLED);
      task.setResolvedAt(now);
      task.setResolvedBy(actor);
      taskRepository.save(task);
      eventPublisher.publish(
          owner,
          WorkflowExecutionEventType.HUMAN_TASK_CANCELLED,
          task.getNodeExecutionId(),
          task.getId(),
          actor,
          Map.of(
              "nodeId", task.getNodeId(),
              "reason", cancellationReason
          ),
          now
      );
    }
  }

  /**
   * Returns whether all forward children, including nested Agent Skills, have
   * reached a real terminal state.
   */
  @Transactional(
      propagation = Propagation.MANDATORY,
      rollbackFor = Exception.class
  )
  public boolean forwardChildrenSettled(
      final AiWorkflowExecution execution,
      final Collection<AiWorkflowNodeExecution> checkpoints
  ) {
    AiWorkflowExecution owner = requireExecution(execution);
    boolean settled = true;
    for (AiWorkflowNodeExecution node : orderedNodes(owner, checkpoints)) {
      NormalBinding binding = normalBinding(node);
      compensationBinding(node);
      if (binding == null) {
        continue;
      }
      if (binding.type() == ChildType.SKILL) {
        AiSkillExecution child = requireSkill(
            owner,
            binding.resourceId(),
            binding.resourceVersionId(),
            binding.executionId(),
            owner.getId() + ":node:" + node.getNodeId()
        );
        settled &= terminal(child.getStatus());
        continue;
      }
      AiAgentExecution child = requireAgent(
          owner,
          binding.resourceId(),
          binding.resourceVersionId(),
          binding.executionId(),
          owner.getId() + ":node:" + node.getNodeId()
      );
      settled &= terminal(child.getStatus());
      List<AiAgentExecutionTrace> traces =
          agentTraceRepository.findAllActiveByExecutionId(child.getId());
      for (AiAgentExecutionTrace trace : traces) {
        if (!Objects.equals(child.getId(), trace.getExecutionId())) {
          throw corruption(node, "has a foreign Agent trace");
        }
        if (trace.getSkillExecutionId() == null) {
          continue;
        }
        if (trace.getType() != AgentTraceType.SKILL) {
          throw corruption(node, "has an invalid nested Skill trace");
        }
        String toolCallId = requiredStored(
            trace.getToolCallId(),
            node,
            "nested Skill tool-call ID"
        );
        AiSkillExecution nested = requireSkill(
            owner,
            requiredStored(
                trace.getSkillId(), node, "nested Skill ID"
            ),
            requiredStored(
                trace.getSkillVersionId(), node, "nested Skill version ID"
            ),
            requiredStored(
                trace.getSkillExecutionId(), node, "nested Skill execution ID"
            ),
            child.getId() + ":" + toolCallId
        );
        settled &= terminal(nested.getStatus());
      }
    }
    return settled;
  }

  private void terminateNode(
      final AiWorkflowExecution execution,
      final AiWorkflowNodeExecution node,
      final String actor,
      final String reason,
      final Instant now
  ) {
    if (NODE_TERMINAL.contains(node.getStatus())) {
      return;
    }
    if (node.getStatus() == WorkflowNodeExecutionStatus.COMPENSATING) {
      CompensationBinding binding = compensationBinding(node);
      AiSkillExecution child = skillExecutionService.cancelPinnedChild(
          new SkillPinnedChildCancelCommand(
              binding.skillId(),
              binding.skillVersionId(),
              binding.executionId(),
              execution.getScopeType(),
              execution.getTenantId(),
              execution.getId() + ":compensate:" + node.getNodeId(),
              actor,
              reason
          )
      );
      assertSkill(
          execution,
          binding.skillId(),
          binding.skillVersionId(),
          binding.executionId(),
          execution.getId() + ":compensate:" + node.getNodeId(),
          child
      );
      mapCompensationResult(execution, node, child, actor, reason, now);
      return;
    }
    NormalBinding binding = normalBinding(node);
    if (binding == null) {
      cancelNode(execution, node, actor, reason, now);
      return;
    }
    if (node.getStatus() != WorkflowNodeExecutionStatus.WAITING) {
      throw corruption(node, "has a child outside its waiting state");
    }
    if (binding.type() == ChildType.AGENT) {
      AiAgentExecution child = agentExecutionService.cancelPinnedChild(
          new AgentPinnedChildCancelCommand(
              binding.resourceId(),
              binding.resourceVersionId(),
              binding.executionId(),
              execution.getScopeType(),
              execution.getTenantId(),
              execution.getId() + ":node:" + node.getNodeId(),
              actor,
              reason
          )
      );
      assertAgent(
          execution,
          binding.resourceId(),
          binding.resourceVersionId(),
          binding.executionId(),
          execution.getId() + ":node:" + node.getNodeId(),
          child
      );
      mapAgentResult(execution, node, child, actor, reason, now);
      return;
    }
    AiSkillExecution child = skillExecutionService.cancelPinnedChild(
        new SkillPinnedChildCancelCommand(
            binding.resourceId(),
            binding.resourceVersionId(),
            binding.executionId(),
            execution.getScopeType(),
            execution.getTenantId(),
            execution.getId() + ":node:" + node.getNodeId(),
            actor,
            reason
        )
    );
    assertSkill(
        execution,
        binding.resourceId(),
        binding.resourceVersionId(),
        binding.executionId(),
        execution.getId() + ":node:" + node.getNodeId(),
        child
    );
    mapSkillResult(execution, node, child, actor, reason, now);
  }

  private void validateStoredChildren(
      final AiWorkflowExecution owner,
      final AiWorkflowNodeExecution node,
      final NormalBinding normal,
      final CompensationBinding compensation
  ) {
    if (normal != null) {
      String stableKey = owner.getId() + ":node:" + node.getNodeId();
      if (normal.type() == ChildType.AGENT) {
        requireAgent(
            owner,
            normal.resourceId(),
            normal.resourceVersionId(),
            normal.executionId(),
            stableKey
        );
      } else {
        requireSkill(
            owner,
            normal.resourceId(),
            normal.resourceVersionId(),
            normal.executionId(),
            stableKey
        );
      }
    }
    if (compensation != null) {
      requireSkill(
          owner,
          compensation.skillId(),
          compensation.skillVersionId(),
          compensation.executionId(),
          owner.getId() + ":compensate:" + node.getNodeId()
      );
    }
  }

  private void mapAgentResult(
      final AiWorkflowExecution execution,
      final AiWorkflowNodeExecution node,
      final AiAgentExecution child,
      final String actor,
      final String reason,
      final Instant now
  ) {
    if (child.getStatus() == AgentExecutionStatus.SUCCEEDED) {
      succeedNode(
          execution,
          node,
          child.getOutputJson(),
          child.getCompletedAt(),
          actor,
          now
      );
    } else if (child.getStatus() == AgentExecutionStatus.FAILED
        || child.getStatus() == AgentExecutionStatus.REJECTED) {
      failNode(
          execution,
          node,
          "WORKFLOW_AGENT_CHILD_" + child.getStatus().name(),
          "WORKFLOW_AGENT_CHILD_" + child.getStatus().name(),
          actor,
          child.getCompletedAt(),
          now
      );
    } else {
      cancelNode(
          execution,
          node,
          actor,
          reason,
          child.getCompletedAt() == null ? now : child.getCompletedAt()
      );
      applyNodeDiagnostic(
          node,
          "WORKFLOW_AGENT_CHILD_" + child.getStatus().name()
      );
      nodeRepository.save(node);
    }
  }

  private void mapSkillResult(
      final AiWorkflowExecution execution,
      final AiWorkflowNodeExecution node,
      final AiSkillExecution child,
      final String actor,
      final String reason,
      final Instant now
  ) {
    if (child.getStatus() == SkillExecutionStatus.SUCCEEDED) {
      succeedNode(
          execution,
          node,
          child.getOutputJson(),
          child.getCompletedAt(),
          actor,
          now
      );
    } else if (child.getStatus() == SkillExecutionStatus.FAILED
        || child.getStatus() == SkillExecutionStatus.REJECTED) {
      failNode(
          execution,
          node,
          "WORKFLOW_SKILL_CHILD_" + child.getStatus().name(),
          "WORKFLOW_SKILL_CHILD_" + child.getStatus().name(),
          actor,
          child.getCompletedAt(),
          now
      );
    } else {
      cancelNode(
          execution,
          node,
          actor,
          reason,
          child.getCompletedAt() == null ? now : child.getCompletedAt()
      );
      applyNodeDiagnostic(
          node,
          "WORKFLOW_SKILL_CHILD_" + child.getStatus().name()
      );
      nodeRepository.save(node);
    }
  }

  private void mapCompensationResult(
      final AiWorkflowExecution execution,
      final AiWorkflowNodeExecution node,
      final AiSkillExecution child,
      final String actor,
      final String reason,
      final Instant now
  ) {
    if (child.getStatus() == SkillExecutionStatus.SUCCEEDED) {
      node.setStatus(WorkflowNodeExecutionStatus.COMPENSATED);
      node.setCompletedAt(
          child.getCompletedAt() == null ? now : child.getCompletedAt()
      );
      nodeRepository.save(node);
      eventPublisher.publish(
          execution,
          WorkflowExecutionEventType.COMPENSATION_SUCCEEDED,
          node.getId(),
          null,
          actor,
          Map.of("nodeId", node.getNodeId()),
          now
      );
    } else if (child.getStatus() == SkillExecutionStatus.FAILED
        || child.getStatus() == SkillExecutionStatus.REJECTED) {
      node.setStatus(WorkflowNodeExecutionStatus.COMPENSATION_FAILED);
      applyNodeDiagnostic(
          node,
          "WORKFLOW_COMPENSATION_CHILD_" + child.getStatus().name()
      );
      node.setCompletedAt(
          child.getCompletedAt() == null ? now : child.getCompletedAt()
      );
      nodeRepository.save(node);
      eventPublisher.publish(
          execution,
          WorkflowExecutionEventType.COMPENSATION_FAILED,
          node.getId(),
          null,
          actor,
          Map.of("nodeId", node.getNodeId(), "code", node.getErrorCode()),
          now
      );
    } else {
      cancelNode(
          execution,
          node,
          actor,
          reason,
          child.getCompletedAt() == null ? now : child.getCompletedAt()
      );
      applyNodeDiagnostic(
          node,
          "WORKFLOW_COMPENSATION_CHILD_" + child.getStatus().name()
      );
      nodeRepository.save(node);
    }
  }

  private void succeedNode(
      final AiWorkflowExecution execution,
      final AiWorkflowNodeExecution node,
      final String outputJson,
      final Instant completedAt,
      final String actor,
      final Instant now
  ) {
    if (outputJson == null || outputJson.isBlank()) {
      throw corruption(node, "has a successful child without output");
    }
    node.setStatus(WorkflowNodeExecutionStatus.SUCCEEDED);
    node.setOutputJson(outputJson);
    node.setOutputHash(sha256(outputJson));
    node.setErrorCode(null);
    node.setErrorMessage(null);
    node.setCompletedAt(completedAt == null ? now : completedAt);
    nodeRepository.save(node);
    eventPublisher.publish(
        execution,
        WorkflowExecutionEventType.NODE_SUCCEEDED,
        node.getId(),
        null,
        actor,
        Map.of("nodeId", node.getNodeId()),
        now
    );
  }

  private void failNode(
      final AiWorkflowExecution execution,
      final AiWorkflowNodeExecution node,
      final String code,
      final String message,
      final String actor,
      final Instant completedAt,
      final Instant now
  ) {
    StableDiagnostic diagnostic = AiExecutionDiagnostics.workflowNode(code);
    node.setStatus(WorkflowNodeExecutionStatus.FAILED);
    node.setErrorCode(diagnostic.errorCode());
    node.setErrorMessage(diagnostic.errorMessage());
    node.setCompletedAt(completedAt == null ? now : completedAt);
    nodeRepository.save(node);
    eventPublisher.publish(
        execution,
        WorkflowExecutionEventType.NODE_FAILED,
        node.getId(),
        null,
        actor,
        Map.of("nodeId", node.getNodeId(), "code", node.getErrorCode()),
        now
    );
  }

  private void cancelNode(
      final AiWorkflowExecution execution,
      final AiWorkflowNodeExecution node,
      final String actor,
      final String reason,
      final Instant now
  ) {
    node.setStatus(WorkflowNodeExecutionStatus.CANCELLED);
    node.setCompletedAt(now);
    applyNodeDiagnostic(node, "WORKFLOW_NODE_CANCELLED");
    nodeRepository.save(node);
    eventPublisher.publish(
        execution,
        WorkflowExecutionEventType.NODE_CANCELLED,
        node.getId(),
        null,
        actor,
        Map.of("nodeId", node.getNodeId(), "reason", node.getErrorCode()),
        now
    );
  }

  private static String terminationReason(
      final AiWorkflowExecution execution
  ) {
    if (execution.getStatus() == WorkflowExecutionStatus.FAILED
        || (execution.getErrorCode() != null
            && !execution.getErrorCode().isBlank())) {
      return AiExecutionDiagnostics.workflowExecution(
          execution.getErrorCode()
      ).errorCode();
    }
    return "WORKFLOW_NODE_CANCELLED";
  }

  private static void applyNodeDiagnostic(
      final AiWorkflowNodeExecution node,
      final String errorCode
  ) {
    StableDiagnostic diagnostic =
        AiExecutionDiagnostics.workflowNode(errorCode);
    node.setErrorCode(diagnostic.errorCode());
    node.setErrorMessage(diagnostic.errorMessage());
  }

  private AiAgentExecution requireAgent(
      final AiWorkflowExecution owner,
      final String resourceId,
      final String versionId,
      final String executionId,
      final String stableKey
  ) {
    AiAgentExecution child = agentExecutionRepository
        .findActiveById(executionId)
        .orElseThrow(() -> new IllegalStateException(
            "Workflow Agent child execution disappeared"
        ));
    assertAgent(owner, resourceId, versionId, executionId, stableKey, child);
    return child;
  }

  private AiSkillExecution requireSkill(
      final AiWorkflowExecution owner,
      final String resourceId,
      final String versionId,
      final String executionId,
      final String stableKey
  ) {
    AiSkillExecution child = skillExecutionRepository
        .findActiveById(executionId)
        .orElseThrow(() -> new IllegalStateException(
            "Workflow Skill child execution disappeared"
        ));
    assertSkill(owner, resourceId, versionId, executionId, stableKey, child);
    return child;
  }

  private static void assertAgent(
      final AiWorkflowExecution owner,
      final String resourceId,
      final String versionId,
      final String executionId,
      final String stableKey,
      final AiAgentExecution child
  ) {
    if (child == null
        || !Objects.equals(executionId, child.getId())
        || !Objects.equals(resourceId, child.getAgentId())
        || !Objects.equals(versionId, child.getAgentVersionId())
        || owner.getScopeType() != child.getScopeType()
        || !Objects.equals(owner.getTenantId(), child.getTenantId())
        || !Objects.equals(
            sha256(stableKey), child.getIdempotencyKeyHash()
        )) {
      throw new IllegalStateException(
          "Workflow Agent child association is corrupted"
      );
    }
  }

  private static void assertSkill(
      final AiWorkflowExecution owner,
      final String resourceId,
      final String versionId,
      final String executionId,
      final String stableKey,
      final AiSkillExecution child
  ) {
    if (child == null
        || child.getSourceType() != SkillExecutionSource.PUBLISHED
        || !Objects.equals(executionId, child.getId())
        || !Objects.equals(resourceId, child.getSkillId())
        || !Objects.equals(versionId, child.getSkillVersionId())
        || owner.getScopeType() != child.getScopeType()
        || !Objects.equals(owner.getTenantId(), child.getTenantId())
        || !Objects.equals(
            sha256(stableKey), child.getIdempotencyKeyHash()
        )) {
      throw new IllegalStateException(
          "Workflow Skill child association is corrupted"
      );
    }
  }

  private static AiWorkflowExecution requireExecution(
      final AiWorkflowExecution execution
  ) {
    if (execution == null || execution.getId() == null
        || execution.getId().isBlank() || execution.getScopeType() == null) {
      throw new IllegalArgumentException(
          "Workflow execution ownership is required for termination"
      );
    }
    return execution;
  }

  private static List<AiWorkflowNodeExecution> orderedNodes(
      final AiWorkflowExecution owner,
      final Collection<AiWorkflowNodeExecution> checkpoints
  ) {
    if (checkpoints == null) {
      throw new IllegalArgumentException(
          "Workflow node checkpoints are required for termination"
      );
    }
    List<AiWorkflowNodeExecution> result = new ArrayList<>(checkpoints);
    Set<Integer> orders = new HashSet<>();
    Set<String> executionNodeIds = new HashSet<>();
    Set<String> nodeIds = new HashSet<>();
    for (AiWorkflowNodeExecution node : result) {
      if (node == null || node.getId() == null || node.getId().isBlank()
          || node.getNodeId() == null || node.getNodeId().isBlank()
          || node.getNodeOrder() == null || node.getStatus() == null
          || !Objects.equals(owner.getId(), node.getExecutionId())
          || !orders.add(node.getNodeOrder())
          || !executionNodeIds.add(node.getId())
          || !nodeIds.add(node.getNodeId())) {
        throw new IllegalStateException(
            "Workflow node checkpoint ordering is corrupted"
        );
      }
    }
    result.sort(Comparator.comparing(AiWorkflowNodeExecution::getNodeOrder));
    return result;
  }

  private static NormalBinding normalBinding(
      final AiWorkflowNodeExecution node
  ) {
    boolean any = node.getChildType() != null
        || node.getChildResourceId() != null
        || node.getChildResourceVersionId() != null
        || node.getChildExecutionId() != null;
    if (!any) {
      if (node.getStatus() == WorkflowNodeExecutionStatus.WAITING
          && ("agent".equals(node.getNodeType())
              || "skill".equals(node.getNodeType()))) {
        throw corruption(node, "is missing its child association");
      }
      return null;
    }
    final String type = requiredStored(
        node.getChildType(), node, "child type"
    );
    final String resourceId = requiredStored(
        node.getChildResourceId(), node, "child resource ID"
    );
    final String versionId = requiredStored(
        node.getChildResourceVersionId(), node, "child version ID"
    );
    final String executionId = requiredStored(
        node.getChildExecutionId(), node, "child execution ID"
    );
    ChildType childType;
    try {
      childType = ChildType.valueOf(type);
    } catch (IllegalArgumentException ex) {
      throw corruption(node, "has an unsupported child type");
    }
    if (!childType.name().toLowerCase().equals(node.getNodeType())) {
      throw corruption(node, "has a child type that does not match the node");
    }
    if (node.getStatus() == WorkflowNodeExecutionStatus.PENDING
        || node.getStatus() == WorkflowNodeExecutionStatus.RUNNING) {
      throw corruption(node, "has a child outside its waiting state");
    }
    return new NormalBinding(childType, resourceId, versionId, executionId);
  }

  private static CompensationBinding compensationBinding(
      final AiWorkflowNodeExecution node
  ) {
    final boolean metadata = node.getCompensationSkillId() != null
        || node.getCompensationSkillVersionId() != null
        || node.getCompensationContentHash() != null;
    if (!metadata) {
      if (node.getCompensationExecutionId() != null
          || node.getStatus() == WorkflowNodeExecutionStatus.COMPENSATING) {
        throw corruption(node, "is missing its compensation binding");
      }
      return null;
    }
    final String skillId = requiredStored(
        node.getCompensationSkillId(), node, "compensation Skill ID"
    );
    final String versionId = requiredStored(
        node.getCompensationSkillVersionId(),
        node,
        "compensation Skill version ID"
    );
    requiredStored(
        node.getCompensationContentHash(),
        node,
        "compensation content hash"
    );
    if (node.getCompensationExecutionId() == null) {
      if (node.getStatus() == WorkflowNodeExecutionStatus.COMPENSATING) {
        throw corruption(node, "is missing its compensation execution");
      }
      return null;
    }
    if (node.getStatus() != WorkflowNodeExecutionStatus.COMPENSATING
        && node.getStatus() != WorkflowNodeExecutionStatus.COMPENSATED
        && node.getStatus()
            != WorkflowNodeExecutionStatus.COMPENSATION_FAILED
        && node.getStatus() != WorkflowNodeExecutionStatus.CANCELLED) {
      throw corruption(node, "has an invalid compensation execution state");
    }
    return new CompensationBinding(
        skillId,
        versionId,
        requiredStored(
            node.getCompensationExecutionId(),
            node,
            "compensation execution ID"
        )
    );
  }

  private static void validateTasks(
      final AiWorkflowExecution owner,
      final Map<String, AiWorkflowNodeExecution> nodes,
      final List<AiWorkflowHumanTask> tasks
  ) {
    Set<String> taskIds = new HashSet<>();
    for (AiWorkflowHumanTask task : tasks) {
      AiWorkflowNodeExecution node = task == null
          ? null : nodes.get(task.getNodeExecutionId());
      if (task == null || task.getId() == null || task.getId().isBlank()
          || !taskIds.add(task.getId())
          || node == null
          || !Objects.equals(owner.getId(), task.getExecutionId())
          || !Objects.equals(node.getNodeId(), task.getNodeId())
          || !"human".equals(node.getNodeType())
          || owner.getScopeType() != task.getScopeType()
          || !Objects.equals(owner.getTenantId(), task.getTenantId())
          || task.getStatus() == null) {
        throw new IllegalStateException(
            "Workflow human task association is corrupted"
        );
      }
    }
  }

  private static boolean terminal(final AgentExecutionStatus status) {
    return status == AgentExecutionStatus.SUCCEEDED
        || status == AgentExecutionStatus.FAILED
        || status == AgentExecutionStatus.REJECTED
        || status == AgentExecutionStatus.CANCELLED;
  }

  private static boolean terminal(final SkillExecutionStatus status) {
    return status == SkillExecutionStatus.SUCCEEDED
        || status == SkillExecutionStatus.FAILED
        || status == SkillExecutionStatus.REJECTED
        || status == SkillExecutionStatus.CANCELLED;
  }

  private static String required(
      final String value,
      final String label,
      final int maximum
  ) {
    if (value == null || value.isBlank() || value.length() > maximum) {
      throw new IllegalArgumentException(
          label + " must be between 1 and " + maximum + " characters"
      );
    }
    return value;
  }

  private static String requiredStored(
      final String value,
      final AiWorkflowNodeExecution node,
      final String label
  ) {
    if (value == null || value.isBlank()) {
      throw corruption(node, "has an invalid " + label);
    }
    return value;
  }

  private static String optional(
      final String value,
      final String fallback
  ) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String bounded(final String value, final int maximum) {
    String safe = value == null ? "Workflow execution was cancelled" : value;
    return safe.length() <= maximum ? safe : safe.substring(0, maximum);
  }

  private static String sha256(final String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(
          digest.digest(value.getBytes(StandardCharsets.UTF_8))
      );
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }

  private static IllegalStateException corruption(
      final AiWorkflowNodeExecution node,
      final String reason
  ) {
    return new IllegalStateException(
        "Workflow node " + node.getNodeId() + " " + reason
    );
  }

  private enum ChildType {
    AGENT,
    SKILL
  }

  private record NormalBinding(
      ChildType type,
      String resourceId,
      String resourceVersionId,
      String executionId
  ) {
  }

  private record CompensationBinding(
      String skillId,
      String skillVersionId,
      String executionId
  ) {
  }
}
