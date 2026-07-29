package org.simplepoint.plugin.ai.skill.service.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecution;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecutionStep;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStepStatus;
import org.simplepoint.plugin.ai.skill.api.properties.SkillExecutionProperties;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillExecutionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillExecutionStepRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short durable state transitions for horizontally safe Skill execution.
 */
@Service
public class AiSkillExecutionCoordinator {

  private final AiSkillExecutionRepository executionRepository;

  private final AiSkillExecutionStepRepository stepRepository;

  private final SkillExecutionProperties properties;

  /**
   * Creates the transactional Skill execution coordinator.
   */
  public AiSkillExecutionCoordinator(
      final AiSkillExecutionRepository executionRepository,
      final AiSkillExecutionStepRepository stepRepository,
      final SkillExecutionProperties properties
  ) {
    this.executionRepository = executionRepository;
    this.stepRepository = stepRepository;
    this.properties = properties;
  }

  /**
   * Claims pending or expired executions with database skip-locked semantics.
   */
  @Transactional(rollbackFor = Exception.class)
  public List<ExecutionTask> claim(final String workerId) {
    Instant now = Instant.now();
    List<AiSkillExecution> candidates =
        executionRepository.findClaimableForUpdate(
            now,
            PageRequest.of(0, batchSize())
        );
    List<ExecutionTask> tasks = new ArrayList<>();
    for (AiSkillExecution execution : candidates) {
      if (execution.getAttemptCount() >= maxAttempts()) {
        execution.setStatus(SkillExecutionStatus.FAILED);
        execution.setCompletedAt(now);
        execution.setErrorCode("SKILL_EXECUTION_RETRY_EXHAUSTED");
        execution.setErrorMessage("Skill execution retry limit was exhausted");
        clearLease(execution);
        executionRepository.save(execution);
        continue;
      }
      execution.setStatus(SkillExecutionStatus.RUNNING);
      execution.setAttemptCount(execution.getAttemptCount() + 1);
      execution.setLeaseOwner(workerId);
      execution.setLeaseToken(execution.getLeaseToken() + 1);
      execution.setLeaseExpiresAt(now.plus(leaseDuration()));
      if (execution.getStartedAt() == null) {
        execution.setStartedAt(now);
      }
      execution.setErrorCode(null);
      execution.setErrorMessage(null);
      executionRepository.save(execution);
      tasks.add(toTask(
          execution,
          stepRepository.findAllActiveByExecutionId(execution.getId())
      ));
    }
    return List.copyOf(tasks);
  }

  /**
   * Checkpoints one step as running and extends the execution lease.
   */
  @Transactional(rollbackFor = Exception.class)
  public void beginStep(
      final ExecutionTask task,
      final String stepId,
      final String inputJson
  ) {
    final AiSkillExecution execution = requireOwned(task);
    AiSkillExecutionStep step = requireStep(task.executionId(), stepId);
    if (step.getStatus() == SkillExecutionStepStatus.SUCCEEDED) {
      return;
    }
    Instant now = Instant.now();
    execution.setCurrentStepId(stepId);
    execution.setLeaseExpiresAt(now.plus(leaseDuration()));
    executionRepository.save(execution);
    step.setStatus(SkillExecutionStepStatus.RUNNING);
    step.setAttemptCount(step.getAttemptCount() + 1);
    step.setInputJson(inputJson);
    step.setOutputJson(null);
    step.setStartedAt(now);
    step.setCompletedAt(null);
    step.setErrorMessage(null);
    stepRepository.save(step);
  }

  /**
   * Stores a successful Tool result checkpoint.
   */
  @Transactional(rollbackFor = Exception.class)
  public void completeStep(
      final ExecutionTask task,
      final String stepId,
      final String outputJson
  ) {
    final AiSkillExecution execution = requireOwned(task);
    AiSkillExecutionStep step = requireStep(task.executionId(), stepId);
    Instant now = Instant.now();
    step.setStatus(SkillExecutionStepStatus.SUCCEEDED);
    step.setOutputJson(outputJson);
    step.setCompletedAt(now);
    step.setErrorMessage(null);
    stepRepository.save(step);
    execution.setLeaseExpiresAt(now.plus(leaseDuration()));
    executionRepository.save(execution);
  }

  /**
   * Completes a workflow after output validation.
   */
  @Transactional(rollbackFor = Exception.class)
  public void succeed(
      final ExecutionTask task,
      final String outputJson
  ) {
    AiSkillExecution execution = requireOwned(task);
    execution.setStatus(SkillExecutionStatus.SUCCEEDED);
    execution.setOutputJson(outputJson);
    execution.setCurrentStepId(null);
    execution.setCompletedAt(Instant.now());
    execution.setErrorCode(null);
    execution.setErrorMessage(null);
    clearLease(execution);
    executionRepository.save(execution);
  }

  /**
   * Persists a terminal workflow or Tool failure.
   */
  @Transactional(rollbackFor = Exception.class)
  public void fail(
      final ExecutionTask task,
      final String stepId,
      final String errorCode,
      final String message
  ) {
    AiSkillExecution execution = requireOwned(task);
    Instant now = Instant.now();
    if (stepId != null) {
      AiSkillExecutionStep step = requireStep(task.executionId(), stepId);
      if (step.getStatus() != SkillExecutionStepStatus.SUCCEEDED) {
        step.setStatus(SkillExecutionStepStatus.FAILED);
        step.setCompletedAt(now);
        step.setErrorMessage(truncate(message));
        stepRepository.save(step);
      }
    }
    execution.setStatus(SkillExecutionStatus.FAILED);
    execution.setCompletedAt(now);
    execution.setErrorCode(errorCode);
    execution.setErrorMessage(truncate(message));
    clearLease(execution);
    executionRepository.save(execution);
  }

  private AiSkillExecution requireOwned(final ExecutionTask task) {
    AiSkillExecution execution = executionRepository.findActiveByIdForUpdate(
        task.executionId()
    ).orElseThrow(() -> new IllegalStateException(
        "Skill execution no longer exists"
    ));
    if (execution.getStatus() != SkillExecutionStatus.RUNNING
        || execution.getLeaseToken() != task.leaseToken()
        || !task.workerId().equals(execution.getLeaseOwner())) {
      throw new IllegalStateException("Skill execution lease is stale");
    }
    return execution;
  }

  private AiSkillExecutionStep requireStep(
      final String executionId,
      final String stepId
  ) {
    return stepRepository.findActiveByExecutionIdAndStepIdForUpdate(
        executionId,
        stepId
    ).orElseThrow(() -> new IllegalStateException(
        "Skill workflow step does not exist"
    ));
  }

  private ExecutionTask toTask(
      final AiSkillExecution execution,
      final List<AiSkillExecutionStep> steps
  ) {
    List<StepTask> stepTasks = steps.stream()
        .map(step -> new StepTask(
            step.getStepId(),
            step.getStepOrder(),
            step.getStatus(),
            step.getMcpServerId(),
            step.getCapabilitySnapshotId(),
            step.getToolName(),
            step.getInputSchemaHash(),
            step.getArgumentsTemplateJson(),
            step.getOutputJson()
        ))
        .toList();
    return new ExecutionTask(
        execution.getId(),
        execution.getScopeType(),
        execution.getTenantId(),
        execution.getRequestedBy(),
        execution.getInputJson(),
        execution.getOutputTemplateJson(),
        execution.getOutputSchemaJson(),
        execution.getLeaseOwner(),
        execution.getLeaseToken(),
        stepTasks
    );
  }

  private int batchSize() {
    Integer configured = properties.getBatchSize();
    return configured == null ? 4 : Math.max(1, Math.min(configured, 32));
  }

  private int maxAttempts() {
    Integer configured = properties.getMaxAttempts();
    return configured == null ? 3 : Math.max(1, Math.min(configured, 20));
  }

  private Duration leaseDuration() {
    Duration configured = properties.getLeaseDuration();
    if (configured == null || configured.compareTo(Duration.ofSeconds(30)) < 0) {
      return Duration.ofMinutes(2);
    }
    return configured.compareTo(Duration.ofMinutes(30)) > 0
        ? Duration.ofMinutes(30) : configured;
  }

  private static void clearLease(final AiSkillExecution execution) {
    execution.setLeaseOwner(null);
    execution.setLeaseExpiresAt(null);
  }

  private static String truncate(final String value) {
    if (value == null || value.isBlank()) {
      return "Skill workflow execution failed";
    }
    String normalized = value.replaceAll("\\s+", " ").trim();
    return normalized.length() <= 1024
        ? normalized : normalized.substring(0, 1024);
  }

  /**
   * Immutable claimed workflow payload executed outside a transaction.
   */
  public record ExecutionTask(
      String executionId,
      AiResourceScope scopeType,
      String tenantId,
      String requestedBy,
      String inputJson,
      String outputTemplateJson,
      String outputSchemaJson,
      String workerId,
      long leaseToken,
      List<StepTask> steps
  ) {
  }

  /**
   * Immutable step checkpoint included in one claimed execution.
   */
  public record StepTask(
      String stepId,
      int stepOrder,
      SkillExecutionStepStatus status,
      String serverId,
      String snapshotId,
      String toolName,
      String inputSchemaHash,
      String argumentsTemplateJson,
      String outputJson
  ) {
  }
}
