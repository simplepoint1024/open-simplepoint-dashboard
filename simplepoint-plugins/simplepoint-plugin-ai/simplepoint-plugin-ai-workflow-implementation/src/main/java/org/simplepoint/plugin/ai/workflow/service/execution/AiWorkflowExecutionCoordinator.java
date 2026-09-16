package org.simplepoint.plugin.ai.workflow.service.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.simplepoint.plugin.ai.core.service.support.AiExecutionDiagnostics;
import org.simplepoint.plugin.ai.core.service.support.AiExecutionDiagnostics.StableDiagnostic;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowExecution;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionEventType;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionStatus;
import org.simplepoint.plugin.ai.workflow.api.properties.WorkflowExecutionProperties;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowExecutionRepository;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowNodeExecutionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short lease and fencing transitions for horizontal Workflow workers.
 */
@Service
public class AiWorkflowExecutionCoordinator {

  private final AiWorkflowExecutionRepository repository;

  private final AiWorkflowExecutionEventPublisher eventPublisher;

  private final AiWorkflowNodeExecutionRepository nodeRepository;

  private final AiWorkflowTerminationCoordinator terminationCoordinator;

  private final WorkflowExecutionProperties properties;

  /**
   * Creates the transactional Workflow coordinator.
   */
  public AiWorkflowExecutionCoordinator(
      final AiWorkflowExecutionRepository repository,
      final AiWorkflowExecutionEventPublisher eventPublisher,
      final AiWorkflowNodeExecutionRepository nodeRepository,
      final AiWorkflowTerminationCoordinator terminationCoordinator,
      final WorkflowExecutionProperties properties
  ) {
    this.repository = repository;
    this.eventPublisher = eventPublisher;
    this.nodeRepository = nodeRepository;
    this.terminationCoordinator = terminationCoordinator;
    this.properties = properties;
  }

  /**
   * Claims due or abandoned executions up to available worker capacity.
   */
  @Transactional(rollbackFor = Exception.class)
  public List<ExecutionTask> claim(
      final String workerId,
      final int availableCapacity
  ) {
    if (availableCapacity <= 0) {
      return List.of();
    }
    Instant now = Instant.now();
    int limit = Math.min(batchSize(), availableCapacity);
    List<AiWorkflowExecution> candidates =
        repository.findClaimableForUpdate(
            now,
            PageRequest.of(0, limit)
        );
    List<ExecutionTask> result = new ArrayList<>();
    for (AiWorkflowExecution execution : candidates) {
      WorkflowExecutionStatus claimedStatus = execution.getStatus();
      if (Boolean.TRUE.equals(execution.getPauseRequested())) {
        pause(execution, now);
        repository.save(execution);
        continue;
      }
      boolean retryClaim = claimedStatus == WorkflowExecutionStatus.RUNNING;
      if (retryClaim
          && execution.getAttemptCount() >= maximumAttempts()) {
        execution.setStatus(WorkflowExecutionStatus.FAILED);
        StableDiagnostic diagnostic =
            AiExecutionDiagnostics.workflowExecution(
                execution.getErrorCode() == null
                    || execution.getErrorCode().isBlank()
                    ? "WORKFLOW_RETRY_EXHAUSTED"
                    : execution.getErrorCode()
            );
        execution.setErrorCode(diagnostic.errorCode());
        execution.setErrorMessage(diagnostic.errorMessage());
        terminationCoordinator.terminate(
            execution,
            nodeRepository.findAllActiveByExecutionId(execution.getId()),
            "workflow-runtime",
            diagnostic.errorCode(),
            now
        );
        execution.setCompletedAt(now);
        clearLease(execution);
        repository.save(execution);
        eventPublisher.publish(
            execution,
            WorkflowExecutionEventType.EXECUTION_FAILED,
            null,
            null,
            "workflow-runtime",
            java.util.Map.of("code", execution.getErrorCode()),
            now
        );
        continue;
      }
      execution.setStatus(
          claimedStatus == WorkflowExecutionStatus.COMPENSATING
              ? WorkflowExecutionStatus.COMPENSATING
              : WorkflowExecutionStatus.RUNNING
      );
      boolean initialClaim = claimedStatus == WorkflowExecutionStatus.PENDING
          && execution.getStartedAt() == null;
      if (initialClaim || retryClaim) {
        execution.setAttemptCount(execution.getAttemptCount() + 1);
      }
      execution.setLeaseOwner(workerId);
      execution.setLeaseToken(execution.getLeaseToken() + 1);
      execution.setLeaseExpiresAt(now.plus(leaseDuration()));
      execution.setNextPollAt(null);
      execution.setInactiveSince(null);
      if (execution.getStartedAt() == null) {
        execution.setStartedAt(now);
        eventPublisher.publish(
            execution,
            WorkflowExecutionEventType.EXECUTION_STARTED,
            null,
            null,
            null,
            java.util.Map.of(),
            now
        );
      }
      repository.save(execution);
      result.add(new ExecutionTask(
          execution.getId(),
          workerId,
          execution.getLeaseToken(),
          claimedStatus
      ));
    }
    return List.copyOf(result);
  }

  /**
   * Extends a live lease only when the exact owner and fencing token match.
   */
  @Transactional(rollbackFor = Exception.class)
  public boolean renewLease(final ExecutionTask task) {
    AiWorkflowExecution execution = repository.findActiveByIdForUpdate(
        task.executionId()
    ).orElse(null);
    Instant now = Instant.now();
    if (execution == null
        || !task.workerId().equals(execution.getLeaseOwner())
        || task.leaseToken() != execution.getLeaseToken()
        || execution.getLeaseExpiresAt() == null
        || !now.isBefore(execution.getLeaseExpiresAt())
        || terminal(execution.getStatus())
        || execution.getStatus() == WorkflowExecutionStatus.PAUSED) {
      return false;
    }
    execution.setLeaseExpiresAt(now.plus(leaseDuration()));
    repository.save(execution);
    return true;
  }

  /**
   * Loads and validates the execution's current lease and fencing token.
   */
  @Transactional(readOnly = true)
  public AiWorkflowExecution requireOwned(final ExecutionTask task) {
    AiWorkflowExecution execution = repository.findActiveById(
        task.executionId()
    ).orElseThrow(() -> new IllegalStateException(
        "Workflow execution no longer exists"
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
        java.util.Map.of(),
        now
    );
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

  private int batchSize() {
    return Math.max(1, properties.getBatchSize() == null
        ? 4 : properties.getBatchSize());
  }

  private int maximumAttempts() {
    return Math.max(1, properties.getMaxAttempts() == null
        ? 32 : properties.getMaxAttempts());
  }

  private Duration leaseDuration() {
    Duration value = properties.getLeaseDuration();
    return value == null || value.isZero() || value.isNegative()
        ? Duration.ofMinutes(2) : value;
  }

  /**
   * Immutable claim token used for all subsequent fenced transitions.
   */
  public record ExecutionTask(
      String executionId,
      String workerId,
      long leaseToken,
      WorkflowExecutionStatus claimedStatus
  ) {
  }

  /**
   * Signals that another worker generation owns the execution.
   */
  public static class StaleWorkflowLeaseException
      extends IllegalStateException {

    /**
     * Creates a stale lease failure.
     */
    public StaleWorkflowLeaseException(final String message) {
      super(message);
    }
  }
}
