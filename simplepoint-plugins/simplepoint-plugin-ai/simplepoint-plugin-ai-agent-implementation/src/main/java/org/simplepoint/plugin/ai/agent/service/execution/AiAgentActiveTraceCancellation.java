package org.simplepoint.plugin.ai.agent.service.execution;

import java.time.Instant;
import java.util.Objects;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecution;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecutionTrace;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceType;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentExecutionTraceRepository;
import org.simplepoint.plugin.ai.skill.api.model.SkillPinnedChildCancelCommand;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillExecutionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closes the exact active trace of an already locked Agent execution.
 */
@Service
public class AiAgentActiveTraceCancellation {

  private static final String CANCELLATION_ERROR_CODE =
      "AGENT_EXECUTION_CANCELLED";

  private final AiAgentExecutionTraceRepository traceRepository;

  private final AiSkillExecutionService skillExecutionService;

  /** Creates the transaction-bound active trace cancellation helper. */
  public AiAgentActiveTraceCancellation(
      final AiAgentExecutionTraceRepository traceRepository,
      final AiSkillExecutionService skillExecutionService
  ) {
    this.traceRepository = traceRepository;
    this.skillExecutionService = skillExecutionService;
  }

  /**
   * Cancels a running Model or Skill trace and clears all active pointers.
   * The caller must already hold the owning Agent execution row lock.
   */
  @Transactional(
      propagation = Propagation.MANDATORY,
      rollbackFor = Exception.class
  )
  public void cancelActiveTrace(
      final AiAgentExecution execution,
      final String actorId,
      final String reason,
      final Instant cancelledAt
  ) {
    if (execution == null || execution.getId() == null) {
      throw new IllegalArgumentException(
          "Locked Agent execution must not be null"
      );
    }
    String actor = required(actorId, "Agent cancellation actor ID", 64);
    String cancellationReason = required(
        reason,
        "Agent cancellation reason",
        1024
    );
    final Instant now = cancelledAt == null ? Instant.now() : cancelledAt;
    String traceId = execution.getCurrentTraceId();
    if (traceId == null) {
      assertNoSkillPointers(execution);
      clearActivePointers(execution);
      return;
    }
    AiAgentExecutionTrace trace = traceRepository.findActiveById(traceId)
        .orElseThrow(() -> new IllegalStateException(
            "Agent active trace no longer exists"
        ));
    if (!traceId.equals(trace.getId())
        || !execution.getId().equals(trace.getExecutionId())) {
      throw new IllegalStateException(
          "Agent active trace escaped its execution boundary"
      );
    }
    if (trace.getType() == AgentTraceType.SKILL) {
      cancelSkillTrace(execution, trace, actor, cancellationReason);
    } else if (trace.getType() == AgentTraceType.MODEL) {
      assertModelTraceRelationship(execution, trace);
    } else {
      throw new IllegalStateException("Agent active trace type is invalid");
    }
    if (trace.getStatus() == AgentTraceStatus.RUNNING) {
      trace.setStatus(AgentTraceStatus.CANCELLED);
      trace.setCompletedAt(now);
      trace.setErrorCode(CANCELLATION_ERROR_CODE);
      trace.setErrorMessage(CANCELLATION_ERROR_CODE);
      traceRepository.save(trace);
    } else if (trace.getStatus() == null) {
      throw new IllegalStateException("Agent active trace status is invalid");
    }
    clearActivePointers(execution);
  }

  private void cancelSkillTrace(
      final AiAgentExecution execution,
      final AiAgentExecutionTrace trace,
      final String actor,
      final String reason
  ) {
    String skillBindingId = required(
        trace.getSkillBindingId(),
        "Agent trace Skill binding ID",
        64
    );
    String skillId = required(
        trace.getSkillId(),
        "Agent trace Skill ID",
        64
    );
    String skillVersionId = required(
        trace.getSkillVersionId(),
        "Agent trace Skill version ID",
        64
    );
    String skillExecutionId = required(
        trace.getSkillExecutionId(),
        "Agent trace Skill execution ID",
        64
    );
    String toolCallId = required(
        trace.getToolCallId(),
        "Agent trace Tool call ID",
        128
    );
    if (!Objects.equals(
        execution.getCurrentSkillBindingId(),
        skillBindingId
    ) || !Objects.equals(
        execution.getCurrentSkillExecutionId(),
        skillExecutionId
    ) || !Objects.equals(execution.getCurrentToolCallId(), toolCallId)) {
      throw new IllegalStateException(
          "Agent active Skill trace relationship is corrupted"
      );
    }
    if (trace.getStatus() == AgentTraceStatus.RUNNING) {
      skillExecutionService.cancelPinnedChild(
          new SkillPinnedChildCancelCommand(
              skillId,
              skillVersionId,
              skillExecutionId,
              execution.getScopeType(),
              execution.getTenantId(),
              execution.getId() + ":" + toolCallId,
              actor,
              reason
          )
      );
    }
  }

  private static void assertModelTraceRelationship(
      final AiAgentExecution execution,
      final AiAgentExecutionTrace trace
  ) {
    String modelDefinitionId = required(
        trace.getModelDefinitionId(),
        "Agent trace Model definition ID",
        64
    );
    assertNoSkillPointers(execution);
    if (!Objects.equals(
        execution.getCurrentModelId(),
        modelDefinitionId
    )) {
      throw new IllegalStateException(
          "Agent active Model trace relationship is corrupted"
      );
    }
  }

  private static void assertNoSkillPointers(
      final AiAgentExecution execution
  ) {
    if (execution.getCurrentSkillBindingId() != null
        || execution.getCurrentSkillExecutionId() != null
        || execution.getCurrentToolCallId() != null) {
      throw new IllegalStateException(
          "Agent execution has orphan active Skill pointers"
      );
    }
  }

  private static void clearActivePointers(
      final AiAgentExecution execution
  ) {
    execution.setCurrentSkillBindingId(null);
    execution.setCurrentSkillExecutionId(null);
    execution.setCurrentToolCallId(null);
    execution.setCurrentTraceId(null);
    execution.setCurrentModelId(null);
  }

  private static String required(
      final String value,
      final String label,
      final int maximumLength
  ) {
    String normalized = value == null ? null : value.trim();
    if (normalized == null || normalized.isEmpty()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    if (normalized.length() > maximumLength) {
      throw new IllegalArgumentException(label + " is too long");
    }
    return normalized;
  }
}
