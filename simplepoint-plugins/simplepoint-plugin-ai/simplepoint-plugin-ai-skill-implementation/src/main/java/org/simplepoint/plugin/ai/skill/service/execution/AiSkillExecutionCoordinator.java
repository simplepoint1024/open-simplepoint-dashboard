package org.simplepoint.plugin.ai.skill.service.execution;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecution;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecutionStep;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionEventType;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionSource;
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

  private final AiSkillExecutionEventPublisher eventPublisher;

  /**
   * Creates the transactional Skill execution coordinator.
   */
  public AiSkillExecutionCoordinator(
      final AiSkillExecutionRepository executionRepository,
      final AiSkillExecutionStepRepository stepRepository,
      final SkillExecutionProperties properties,
      final AiSkillExecutionEventPublisher eventPublisher
  ) {
    this.executionRepository = executionRepository;
    this.stepRepository = stepRepository;
    this.properties = properties;
    this.eventPublisher = eventPublisher;
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
      if (Boolean.TRUE.equals(execution.getPauseRequested())) {
        pauseAtCheckpoint(execution, now);
        executionRepository.save(execution);
        publish(
            execution,
            SkillExecutionEventType.EXECUTION_PAUSED,
            execution.getCurrentStepId(),
            workerId,
            Map.of(),
            now
        );
        continue;
      }
      if (execution.getDeadlineAt() != null
          && !now.isBefore(execution.getDeadlineAt())) {
        execution.setStatus(SkillExecutionStatus.FAILED);
        execution.setCompletedAt(now);
        execution.setErrorCode("SKILL_BUDGET_TIME_EXCEEDED");
        execution.setErrorMessage("Skill execution time budget was exhausted");
        clearLease(execution);
        executionRepository.save(execution);
        publishFailure(execution, workerId, now);
        continue;
      }
      if (execution.getAttemptCount() >= maxAttempts()) {
        execution.setStatus(SkillExecutionStatus.FAILED);
        execution.setCompletedAt(now);
        execution.setErrorCode("SKILL_EXECUTION_RETRY_EXHAUSTED");
        execution.setErrorMessage("Skill execution retry limit was exhausted");
        clearLease(execution);
        executionRepository.save(execution);
        publishFailure(execution, workerId, now);
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
      publish(
          execution,
          SkillExecutionEventType.EXECUTION_STARTED,
          execution.getCurrentStepId(),
          workerId,
          Map.of("attempt", execution.getAttemptCount()),
          now
      );
      tasks.add(toTask(
          execution,
          stepRepository.findAllActiveByExecutionId(execution.getId())
      ));
    }
    return List.copyOf(tasks);
  }

  /**
   * Checkpoints one MCP step as running and extends the execution lease.
   */
  @Transactional(rollbackFor = Exception.class)
  public boolean beginStep(
      final ExecutionTask task,
      final String stepId,
      final String inputJson,
      final String capabilityTokenIdHash
  ) {
    return beginStepInternal(
        task,
        stepId,
        inputJson,
        capabilityTokenIdHash,
        true
    );
  }

  /**
   * Starts an MCP step inside an atomic parallel fork/join region.
   */
  @Transactional(rollbackFor = Exception.class)
  public boolean beginParallelStep(
      final ExecutionTask task,
      final String stepId,
      final String inputJson,
      final String capabilityTokenIdHash
  ) {
    return beginStepInternal(
        task,
        stepId,
        inputJson,
        capabilityTokenIdHash,
        false
    );
  }

  private boolean beginStepInternal(
      final ExecutionTask task,
      final String stepId,
      final String inputJson,
      final String capabilityTokenIdHash,
      final boolean pauseCheckpoint
  ) {
    final AiSkillExecution execution = requireOwned(task);
    Instant now = Instant.now();
    if (cancelAtCheckpoint(execution, task.workerId(), now)) {
      return false;
    }
    if (pauseAtBreakpoint(execution, stepId, task.workerId(), now)) {
      return false;
    }
    if (pauseCheckpoint && pauseAtCheckpoint(execution, now)) {
      executionRepository.save(execution);
      publish(
          execution,
          SkillExecutionEventType.EXECUTION_PAUSED,
          execution.getCurrentStepId(),
          task.workerId(),
          Map.of(),
          now
      );
      return false;
    }
    AiSkillExecutionStep step = requireStep(task.executionId(), stepId);
    if (step.getStatus() == SkillExecutionStepStatus.SUCCEEDED) {
      return true;
    }
    assertWithinDeadline(execution, now);
    if ("tool".equals(step.getStepType())) {
      reserveToolCall(execution, payloadBytes(inputJson));
    } else {
      reservePayload(execution, payloadBytes(inputJson));
    }
    execution.setCurrentStepId(stepId);
    execution.setLeaseExpiresAt(now.plus(leaseDuration()));
    executionRepository.save(execution);
    step.setStatus(SkillExecutionStepStatus.RUNNING);
    step.setAttemptCount(step.getAttemptCount() + 1);
    step.setInputJson(inputJson);
    step.setCapabilityTokenIdHash(capabilityTokenIdHash);
    step.setOutputJson(null);
    step.setStartedAt(now);
    step.setCompletedAt(null);
    step.setErrorCode(null);
    step.setErrorMessage(null);
    stepRepository.save(step);
    publish(
        execution,
        SkillExecutionEventType.STEP_STARTED,
        stepId,
        task.workerId(),
        Map.of(
            "attempt", step.getAttemptCount(),
            "stepType", step.getStepType(),
            "capabilityAlias", safe(step.getCapabilityAlias())
        ),
        now
    );
    return true;
  }

  /**
   * Stores a successful MCP result checkpoint.
   */
  @Transactional(rollbackFor = Exception.class)
  public boolean completeStep(
      final ExecutionTask task,
      final String stepId,
      final String outputJson
  ) {
    return completeStepInternal(task, stepId, outputJson, true);
  }

  /**
   * Completes an MCP step without pausing inside a parallel fork/join region.
   */
  @Transactional(rollbackFor = Exception.class)
  public boolean completeParallelStep(
      final ExecutionTask task,
      final String stepId,
      final String outputJson
  ) {
    return completeStepInternal(task, stepId, outputJson, false);
  }

  private boolean completeStepInternal(
      final ExecutionTask task,
      final String stepId,
      final String outputJson,
      final boolean pauseCheckpoint
  ) {
    final AiSkillExecution execution = requireOwned(task);
    AiSkillExecutionStep step = requireStep(task.executionId(), stepId);
    Instant now = Instant.now();
    assertWithinDeadline(execution, now);
    reservePayload(execution, payloadBytes(outputJson));
    step.setStatus(SkillExecutionStepStatus.SUCCEEDED);
    step.setOutputJson(outputJson);
    step.setCompletedAt(now);
    step.setErrorCode(null);
    step.setErrorMessage(null);
    stepRepository.save(step);
    publish(
        execution,
        SkillExecutionEventType.STEP_SUCCEEDED,
        stepId,
        task.workerId(),
        stepTiming(step, now),
        now
    );
    boolean paused = pauseCheckpoint && pauseAtCheckpoint(execution, now);
    if (!paused) {
      execution.setLeaseExpiresAt(now.plus(leaseDuration()));
    }
    executionRepository.save(execution);
    if (cancelAtCheckpoint(execution, task.workerId(), now)) {
      return false;
    }
    if (paused) {
      publish(
          execution,
          SkillExecutionEventType.EXECUTION_PAUSED,
          stepId,
          task.workerId(),
          Map.of(),
          now
      );
    }
    return !paused;
  }

  /**
   * Applies pause and deadline checks at a declarative control-flow boundary.
   */
  @Transactional(rollbackFor = Exception.class)
  public boolean checkpoint(final ExecutionTask task) {
    AiSkillExecution execution = requireOwned(task);
    Instant now = Instant.now();
    assertWithinDeadline(execution, now);
    if (cancelAtCheckpoint(execution, task.workerId(), now)) {
      return false;
    }
    if (pauseAtCheckpoint(execution, now)) {
      executionRepository.save(execution);
      publish(
          execution,
          SkillExecutionEventType.EXECUTION_PAUSED,
          execution.getCurrentStepId(),
          task.workerId(),
          Map.of(),
          now
      );
      return false;
    }
    execution.setLeaseExpiresAt(now.plus(leaseDuration()));
    executionRepository.save(execution);
    return true;
  }

  /**
   * Persists MCP leaves excluded by a condition as deterministic skips.
   */
  @Transactional(rollbackFor = Exception.class)
  public boolean skipSteps(
      final ExecutionTask task,
      final List<String> stepIds
  ) {
    AiSkillExecution execution = requireOwned(task);
    Instant now = Instant.now();
    assertWithinDeadline(execution, now);
    if (cancelAtCheckpoint(execution, task.workerId(), now)) {
      return false;
    }
    if (pauseAtCheckpoint(execution, now)) {
      executionRepository.save(execution);
      publish(
          execution,
          SkillExecutionEventType.EXECUTION_PAUSED,
          execution.getCurrentStepId(),
          task.workerId(),
          Map.of(),
          now
      );
      return false;
    }
    final String outputJson = "{\"skipped\":true}";
    for (String stepId : stepIds) {
      AiSkillExecutionStep step = requireStep(task.executionId(), stepId);
      if (step.getStatus() == SkillExecutionStepStatus.SUCCEEDED
          || step.getStatus() == SkillExecutionStepStatus.SKIPPED) {
        continue;
      }
      if (step.getStatus() == SkillExecutionStepStatus.FAILED) {
        throw new IllegalStateException(
            "Failed workflow step cannot be skipped"
        );
      }
      reservePayload(execution, payloadBytes(outputJson));
      step.setStatus(SkillExecutionStepStatus.SKIPPED);
      step.setOutputJson(outputJson);
      step.setCompletedAt(now);
      step.setErrorCode(null);
      step.setErrorMessage(null);
      stepRepository.save(step);
      publish(
          execution,
          SkillExecutionEventType.STEP_SKIPPED,
          stepId,
          task.workerId(),
          Map.of("stepType", step.getStepType()),
          now
      );
    }
    execution.setLeaseExpiresAt(now.plus(leaseDuration()));
    executionRepository.save(execution);
    return true;
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
    Instant now = Instant.now();
    assertWithinDeadline(execution, now);
    if (cancelAtCheckpoint(execution, task.workerId(), now)) {
      return;
    }
    if (pauseAtCheckpoint(execution, now)) {
      executionRepository.save(execution);
      publish(
          execution,
          SkillExecutionEventType.EXECUTION_PAUSED,
          execution.getCurrentStepId(),
          task.workerId(),
          Map.of(),
          now
      );
      return;
    }
    reservePayload(execution, payloadBytes(outputJson));
    execution.setStatus(SkillExecutionStatus.SUCCEEDED);
    execution.setOutputJson(outputJson);
    execution.setCurrentStepId(null);
    execution.setCompletedAt(now);
    execution.setErrorCode(null);
    execution.setErrorMessage(null);
    clearLease(execution);
    executionRepository.save(execution);
    publish(
        execution,
        SkillExecutionEventType.EXECUTION_SUCCEEDED,
        null,
        task.workerId(),
        Map.of(
            "consumedToolCalls", consumedToolCalls(execution),
            "consumedPayloadBytes", consumedPayloadBytes(execution)
        ),
        now
    );
  }

  /**
   * Atomically stores a MOCK output and its pinned assertion results.
   */
  @Transactional(rollbackFor = Exception.class)
  public void completeMock(
      final ExecutionTask task,
      final String outputJson,
      final String assertionResultsJson,
      final boolean assertionsPassed
  ) {
    AiSkillExecution execution = requireOwned(task);
    Instant now = Instant.now();
    assertWithinDeadline(execution, now);
    if (cancelAtCheckpoint(execution, task.workerId(), now)) {
      return;
    }
    if (pauseAtCheckpoint(execution, now)) {
      executionRepository.save(execution);
      publish(
          execution,
          SkillExecutionEventType.EXECUTION_PAUSED,
          execution.getCurrentStepId(),
          task.workerId(),
          Map.of(),
          now
      );
      return;
    }
    reservePayload(execution, payloadBytes(outputJson));
    reservePayload(execution, payloadBytes(assertionResultsJson));
    execution.setOutputJson(outputJson);
    execution.setAssertionsPassed(assertionsPassed);
    execution.setAssertionResultsJson(assertionResultsJson);
    execution.setCurrentStepId(null);
    execution.setCompletedAt(now);
    clearLease(execution);
    if (assertionsPassed) {
      execution.setStatus(SkillExecutionStatus.SUCCEEDED);
      execution.setErrorCode(null);
      execution.setErrorMessage(null);
      executionRepository.save(execution);
      publish(
          execution,
          SkillExecutionEventType.EXECUTION_SUCCEEDED,
          null,
          task.workerId(),
          Map.of(
              "assertionsPassed", true,
              "consumedToolCalls", consumedToolCalls(execution),
              "consumedPayloadBytes", consumedPayloadBytes(execution)
          ),
          now
      );
      return;
    }
    execution.setStatus(SkillExecutionStatus.FAILED);
    execution.setErrorCode("SKILL_MOCK_ASSERTION_FAILED");
    execution.setErrorMessage("One or more MOCK test assertions failed");
    executionRepository.save(execution);
    publishFailure(execution, task.workerId(), now);
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
    failInternal(
        task,
        stepId == null ? List.of() : List.of(stepId),
        errorCode,
        message
    );
  }

  /**
   * Persists all failed leaves from a completed parallel fork/join.
   */
  @Transactional(rollbackFor = Exception.class)
  public void fail(
      final ExecutionTask task,
      final List<String> stepIds,
      final String errorCode,
      final String message
  ) {
    failInternal(task, stepIds, errorCode, message);
  }

  private void failInternal(
      final ExecutionTask task,
      final List<String> stepIds,
      final String errorCode,
      final String message
  ) {
    AiSkillExecution execution = requireOwned(task);
    Instant now = Instant.now();
    if (cancelAtCheckpoint(execution, task.workerId(), now)) {
      return;
    }
    for (String stepId : stepIds) {
      AiSkillExecutionStep step = requireStep(task.executionId(), stepId);
      if (step.getStatus() != SkillExecutionStepStatus.SUCCEEDED
          && step.getStatus() != SkillExecutionStepStatus.SKIPPED) {
        step.setStatus(SkillExecutionStepStatus.FAILED);
        step.setCompletedAt(now);
        step.setErrorCode(truncateCode(errorCode));
        step.setErrorMessage(truncate(message));
        stepRepository.save(step);
        publish(
            execution,
            SkillExecutionEventType.STEP_FAILED,
            stepId,
            task.workerId(),
            Map.of(
                "errorCode", safe(errorCode),
                "errorMessage", truncate(message)
            ),
            now
        );
      }
    }
    execution.setStatus(SkillExecutionStatus.FAILED);
    execution.setCompletedAt(now);
    execution.setErrorCode(errorCode);
    execution.setErrorMessage(truncate(message));
    clearLease(execution);
    executionRepository.save(execution);
    publishFailure(execution, task.workerId(), now);
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
            step.getStepType(),
            step.getCapabilityAlias(),
            step.getMcpServerId(),
            step.getCapabilitySnapshotId(),
            step.getCapabilityName(),
            step.getCapabilitySchemaHash(),
            Boolean.TRUE.equals(step.getCapabilityTemplate()),
            step.getInputTemplateJson(),
            step.getOutputJson()
        ))
        .toList();
    return new ExecutionTask(
        execution.getId(),
        execution.getSkillId(),
        runtimeReleaseReference(execution),
        execution.getScopeType(),
        execution.getTenantId(),
        execution.getRequestedBy(),
        execution.getDebugMode(),
        execution.getMockConfigJson(),
        execution.getMockConfigHash(),
        execution.getInputJson(),
        execution.getWorkflowPlanJson(),
        execution.getOutputTemplateJson(),
        execution.getOutputSchemaJson(),
        maximumToolCalls(execution),
        maximumDurationSeconds(execution),
        maximumPayloadBytes(execution),
        consumedToolCalls(execution),
        consumedPayloadBytes(execution),
        execution.getDeadlineAt(),
        execution.getLeaseOwner(),
        execution.getLeaseToken(),
        stepTasks
    );
  }

  private static String runtimeReleaseReference(
      final AiSkillExecution execution
  ) {
    if (execution.getSourceType() == SkillExecutionSource.PUBLISHED) {
      if (execution.getSkillVersionId() == null
          || execution.getSkillVersionId().isBlank()) {
        throw new IllegalStateException(
            "Published Skill execution has no pinned version"
        );
      }
      return execution.getSkillVersionId();
    }
    if (execution.getSourceType() == SkillExecutionSource.DRAFT
        && execution.getDraftId() != null
        && execution.getDraftRevision() != null) {
      return "draft-" + execution.getDraftId()
          + "-" + execution.getDraftRevision();
    }
    throw new IllegalStateException(
        "Skill execution has no immutable runtime release reference"
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

  private static void assertWithinDeadline(
      final AiSkillExecution execution,
      final Instant now
  ) {
    if (execution.getDeadlineAt() != null
        && !now.isBefore(execution.getDeadlineAt())) {
      throw new SkillExecutionBudgetExceededException(
          "SKILL_BUDGET_TIME_EXCEEDED",
          "Skill execution time budget was exhausted"
      );
    }
  }

  private static void reserveToolCall(
      final AiSkillExecution execution,
      final long payloadBytes
  ) {
    int consumed = consumedToolCalls(execution);
    if (consumed >= maximumToolCalls(execution)) {
      throw new SkillExecutionBudgetExceededException(
          "SKILL_BUDGET_TOOL_CALLS_EXCEEDED",
          "Skill execution Tool call budget was exhausted"
      );
    }
    reservePayload(execution, payloadBytes);
    execution.setConsumedToolCalls(consumed + 1);
  }

  private static void reservePayload(
      final AiSkillExecution execution,
      final long payloadBytes
  ) {
    long consumed = consumedPayloadBytes(execution);
    long maximum = maximumPayloadBytes(execution);
    if (payloadBytes < 0 || consumed > maximum - payloadBytes) {
      throw new SkillExecutionBudgetExceededException(
          "SKILL_BUDGET_PAYLOAD_EXCEEDED",
          "Skill execution payload budget was exhausted"
      );
    }
    execution.setConsumedPayloadBytes(consumed + payloadBytes);
  }

  private static int maximumToolCalls(final AiSkillExecution execution) {
    Integer value = execution.getMaximumToolCalls();
    return value == null ? 128 : Math.max(1, value);
  }

  private static int maximumDurationSeconds(
      final AiSkillExecution execution
  ) {
    Integer value = execution.getMaximumDurationSeconds();
    return value == null ? 300 : Math.max(1, value);
  }

  private static long maximumPayloadBytes(final AiSkillExecution execution) {
    Long value = execution.getMaximumPayloadBytes();
    return value == null ? 4L * 1024L * 1024L : Math.max(1024L, value);
  }

  private static int consumedToolCalls(final AiSkillExecution execution) {
    Integer value = execution.getConsumedToolCalls();
    return value == null ? 0 : Math.max(0, value);
  }

  private static long consumedPayloadBytes(final AiSkillExecution execution) {
    Long value = execution.getConsumedPayloadBytes();
    return value == null ? 0L : Math.max(0L, value);
  }

  private static long payloadBytes(final String json) {
    return json == null ? 0L : json.getBytes(StandardCharsets.UTF_8).length;
  }

  private static void clearLease(final AiSkillExecution execution) {
    execution.setLeaseOwner(null);
    execution.setLeaseExpiresAt(null);
  }

  private static boolean pauseAtCheckpoint(
      final AiSkillExecution execution,
      final Instant now
  ) {
    if (!Boolean.TRUE.equals(execution.getPauseRequested())) {
      return false;
    }
    execution.setStatus(SkillExecutionStatus.PAUSED);
    execution.setPausedAt(now);
    if (execution.getInactiveSince() == null) {
      execution.setInactiveSince(now);
    }
    clearLease(execution);
    return true;
  }

  private boolean pauseAtBreakpoint(
      final AiSkillExecution execution,
      final String stepId,
      final String actor,
      final Instant now
  ) {
    if (stepId == null || execution.getBreakpointStepIdsJson() == null) {
      return false;
    }
    if (stepId.equals(execution.getBreakpointBypassedStepId())) {
      execution.setBreakpointBypassedStepId(null);
      executionRepository.save(execution);
      return false;
    }
    if (!execution.getBreakpointStepIdsJson().contains(
        "\"" + stepId + "\""
    )) {
      return false;
    }
    execution.setCurrentStepId(stepId);
    execution.setPauseRequested(true);
    execution.setPauseRequestedAt(now);
    execution.setPauseRequestedBy(actor);
    execution.setPauseReason("Breakpoint before workflow step " + stepId);
    execution.setStatus(SkillExecutionStatus.PAUSED);
    execution.setPausedAt(now);
    if (execution.getInactiveSince() == null) {
      execution.setInactiveSince(now);
    }
    clearLease(execution);
    executionRepository.save(execution);
    publish(
        execution,
        SkillExecutionEventType.EXECUTION_PAUSED,
        stepId,
        actor,
        Map.of("breakpoint", true),
        now
    );
    return true;
  }

  private boolean cancelAtCheckpoint(
      final AiSkillExecution execution,
      final String actor,
      final Instant now
  ) {
    if (!Boolean.TRUE.equals(execution.getCancelRequested())) {
      return false;
    }
    List<AiSkillExecutionStep> steps =
        stepRepository.findAllActiveByExecutionId(execution.getId());
    for (AiSkillExecutionStep step : steps) {
      if (step.getStatus() == SkillExecutionStepStatus.PENDING
          || step.getStatus() == SkillExecutionStepStatus.RUNNING) {
        step.setStatus(SkillExecutionStepStatus.SKIPPED);
        step.setCompletedAt(now);
        step.setErrorCode("SKILL_EXECUTION_CANCELLED");
        step.setErrorMessage("Execution cancelled at a safe checkpoint");
        stepRepository.save(step);
      }
    }
    execution.setStatus(SkillExecutionStatus.CANCELLED);
    execution.setCompletedAt(now);
    execution.setPauseRequested(false);
    execution.setInactiveSince(null);
    clearLease(execution);
    executionRepository.save(execution);
    publish(
        execution,
        SkillExecutionEventType.EXECUTION_CANCELLED,
        execution.getCurrentStepId(),
        execution.getCancelRequestedBy() == null
            ? actor : execution.getCancelRequestedBy(),
        Map.of(),
        now
    );
    return true;
  }

  private static String truncate(final String value) {
    if (value == null || value.isBlank()) {
      return "Skill workflow execution failed";
    }
    String normalized = value.replaceAll("\\s+", " ").trim();
    return normalized.length() <= 1024
        ? normalized : normalized.substring(0, 1024);
  }

  private static String truncateCode(final String value) {
    if (value == null || value.isBlank()) {
      return "SKILL_EXECUTION_FAILED";
    }
    String normalized = value.replaceAll("\\s+", "_").trim();
    return normalized.length() <= 128
        ? normalized : normalized.substring(0, 128);
  }

  private void publishFailure(
      final AiSkillExecution execution,
      final String actorId,
      final Instant occurredAt
  ) {
    publish(
        execution,
        SkillExecutionEventType.EXECUTION_FAILED,
        execution.getCurrentStepId(),
        actorId,
        Map.of(
            "errorCode", safe(execution.getErrorCode()),
            "errorMessage", truncate(execution.getErrorMessage())
        ),
        occurredAt
    );
  }

  private void publish(
      final AiSkillExecution execution,
      final SkillExecutionEventType type,
      final String stepId,
      final String actorId,
      final Map<String, Object> payload,
      final Instant occurredAt
  ) {
    eventPublisher.publish(
        execution,
        type,
        stepId,
        actorId,
        payload,
        occurredAt
    );
  }

  private static Map<String, Object> stepTiming(
      final AiSkillExecutionStep step,
      final Instant completedAt
  ) {
    long durationMillis = step.getStartedAt() == null
        ? 0 : Math.max(
            0,
            Duration.between(step.getStartedAt(), completedAt).toMillis()
        );
    return Map.of(
        "attempt", step.getAttemptCount(),
        "stepType", step.getStepType(),
        "durationMillis", durationMillis
    );
  }

  private static String safe(final String value) {
    return value == null ? "" : value;
  }

  /**
   * Immutable claimed workflow payload executed outside a transaction.
   */
  public record ExecutionTask(
      String executionId,
      String skillId,
      String skillVersionId,
      AiResourceScope scopeType,
      String tenantId,
      String requestedBy,
      org.simplepoint.plugin.ai.skill.api.model.SkillDebugMode debugMode,
      String mockConfigJson,
      String mockConfigHash,
      String inputJson,
      String workflowPlanJson,
      String outputTemplateJson,
      String outputSchemaJson,
      int maximumToolCalls,
      int maximumDurationSeconds,
      long maximumPayloadBytes,
      int consumedToolCalls,
      long consumedPayloadBytes,
      Instant deadlineAt,
      String workerId,
      long leaseToken,
      List<StepTask> steps
  ) {

    /** Compatibility constructor for non-debug and existing test tasks. */
    public ExecutionTask(
        final String executionId,
        final String skillId,
        final String skillVersionId,
        final AiResourceScope scopeType,
        final String tenantId,
        final String requestedBy,
        final String inputJson,
        final String workflowPlanJson,
        final String outputTemplateJson,
        final String outputSchemaJson,
        final int maximumToolCalls,
        final int maximumDurationSeconds,
        final long maximumPayloadBytes,
        final int consumedToolCalls,
        final long consumedPayloadBytes,
        final Instant deadlineAt,
        final String workerId,
        final long leaseToken,
        final List<StepTask> steps
    ) {
      this(
          executionId,
          skillId,
          skillVersionId,
          scopeType,
          tenantId,
          requestedBy,
          null,
          null,
          null,
          inputJson,
          workflowPlanJson,
          outputTemplateJson,
          outputSchemaJson,
          maximumToolCalls,
          maximumDurationSeconds,
          maximumPayloadBytes,
          consumedToolCalls,
          consumedPayloadBytes,
          deadlineAt,
          workerId,
          leaseToken,
          steps
      );
    }
  }

  /**
   * Immutable step checkpoint included in one claimed execution.
   */
  public record StepTask(
      String stepId,
      int stepOrder,
      SkillExecutionStepStatus status,
      String stepType,
      String capabilityAlias,
      String serverId,
      String snapshotId,
      String capabilityName,
      String capabilitySchemaHash,
      boolean capabilityTemplate,
      String inputTemplateJson,
      String outputJson
  ) {
  }
}
