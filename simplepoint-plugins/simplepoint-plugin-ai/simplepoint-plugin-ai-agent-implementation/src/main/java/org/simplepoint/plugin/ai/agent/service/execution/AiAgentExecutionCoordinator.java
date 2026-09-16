package org.simplepoint.plugin.ai.agent.service.execution;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecution;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecutionTrace;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentHumanIntervention;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionEventType;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentHumanInterventionStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentHumanInterventionTimeoutAction;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceType;
import org.simplepoint.plugin.ai.agent.api.properties.AgentExecutionProperties;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentExecutionRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentExecutionTraceRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentHumanInterventionRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentMemoryRepository;
import org.simplepoint.plugin.ai.agent.api.vo.AiAgentMemoryContext;
import org.simplepoint.plugin.ai.agent.api.vo.AiAgentMemoryWrite;
import org.simplepoint.plugin.ai.core.service.support.AiExecutionDiagnostics;
import org.simplepoint.plugin.ai.core.service.support.AiExecutionDiagnostics.StableDiagnostic;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecution;
import org.simplepoint.plugin.ai.skill.api.model.SkillAgentExecutionCommand;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillExecutionService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short, fenced state transitions for horizontally scaled Agent workers.
 */
@Service
public class AiAgentExecutionCoordinator {

  private final AiAgentExecutionRepository executionRepository;

  private final AiAgentExecutionTraceRepository traceRepository;

  private final AiAgentHumanInterventionRepository interventionRepository;

  private final AiAgentExecutionEventPublisher eventPublisher;

  private final AiAgentMemoryRepository memoryRepository;

  private final AiAgentActiveTraceCancellation activeTraceCancellation;

  private final AiSkillExecutionService skillExecutionService;

  private final AgentExecutionProperties properties;

  /**
   * Creates the Agent execution coordinator.
   */
  public AiAgentExecutionCoordinator(
      final AiAgentExecutionRepository executionRepository,
      final AiAgentExecutionTraceRepository traceRepository,
      final AiAgentHumanInterventionRepository interventionRepository,
      final AiAgentExecutionEventPublisher eventPublisher,
      final AiAgentMemoryRepository memoryRepository,
      final AiAgentActiveTraceCancellation activeTraceCancellation,
      final AiSkillExecutionService skillExecutionService,
      final AgentExecutionProperties properties
  ) {
    this.executionRepository = executionRepository;
    this.traceRepository = traceRepository;
    this.interventionRepository = interventionRepository;
    this.eventPublisher = eventPublisher;
    this.memoryRepository = memoryRepository;
    this.activeTraceCancellation = activeTraceCancellation;
    this.skillExecutionService = skillExecutionService;
    this.properties = properties;
  }

  /**
   * Claims pending, child-waiting, or expired work with skip-locked semantics.
   */
  @Transactional(rollbackFor = Exception.class)
  public List<ExecutionTask> claim(final String workerId) {
    return claim(workerId, batchSize());
  }

  /**
   * Claims no more than the currently available worker capacity.
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
    List<AiAgentExecution> candidates =
        executionRepository.findClaimableForUpdate(
            now,
            PageRequest.of(
                0,
                Math.min(batchSize(), Math.max(1, availableCapacity))
            )
        );
    List<ExecutionTask> tasks = new ArrayList<>();
    for (AiAgentExecution execution : candidates) {
      AgentExecutionStatus previous = execution.getStatus();
      if (previous == AgentExecutionStatus.WAITING_HUMAN) {
        expireHumanIntervention(execution, now);
        executionRepository.save(execution);
        continue;
      }
      if (previous == AgentExecutionStatus.RUNNING) {
        reconcileExpiredModelInvocation(execution, now);
      }
      if (previous != AgentExecutionStatus.WAITING_SKILL
          && execution.getAttemptCount() >= maxAttempts()) {
        failExecution(
            execution,
            now,
            "AGENT_EXECUTION_RETRY_EXHAUSTED",
            "Agent execution retry limit was exhausted"
        );
        executionRepository.save(execution);
        continue;
      }
      execution.setStatus(AgentExecutionStatus.RUNNING);
      if (previous != AgentExecutionStatus.WAITING_SKILL) {
        execution.setAttemptCount(execution.getAttemptCount() + 1);
      }
      execution.setLeaseOwner(workerId);
      execution.setLeaseToken(execution.getLeaseToken() + 1);
      execution.setLeaseExpiresAt(now.plus(leaseDuration()));
      execution.setNextPollAt(null);
      final boolean firstStart = execution.getStartedAt() == null;
      if (execution.getStartedAt() == null) {
        execution.setStartedAt(now);
      }
      execution.setErrorCode(null);
      execution.setErrorMessage(null);
      executionRepository.save(execution);
      if (firstStart) {
        publish(
            execution,
            AgentExecutionEventType.EXECUTION_STARTED,
            null,
            null,
            Map.of("workerId", workerId),
            now
        );
      }
      tasks.add(new ExecutionTask(
          execution.getId(),
          workerId,
          execution.getLeaseToken()
      ));
    }
    return List.copyOf(tasks);
  }

  private void reconcileExpiredModelInvocation(
      final AiAgentExecution execution,
      final Instant now
  ) {
    String traceId = execution.getCurrentTraceId();
    if (traceId == null) {
      execution.setCurrentModelId(null);
      return;
    }
    AiAgentExecutionTrace trace = traceRepository.findActiveById(traceId)
        .filter(candidate -> execution.getId().equals(
            candidate.getExecutionId()
        ))
        .orElse(null);
    if (trace == null || trace.getType() != AgentTraceType.MODEL) {
      return;
    }
    if (trace.getStatus() == AgentTraceStatus.RUNNING) {
      trace.setStatus(AgentTraceStatus.FAILED);
      trace.setCompletedAt(now);
      applyTraceDiagnostic(trace, "AGENT_RUNTIME_LEASE_EXPIRED");
      traceRepository.save(trace);
      publish(
          execution,
          AgentExecutionEventType.MODEL_FAILED,
          trace.getId(),
          null,
          payloadError(trace.getErrorCode()),
          now
      );
    }
    execution.setCurrentTraceId(null);
    execution.setCurrentModelId(null);
  }

  /**
   * Renews an active lease while the worker performs bounded external I/O.
   */
  @Transactional(rollbackFor = Exception.class)
  public boolean renewLease(final ExecutionTask task) {
    AiAgentExecution execution =
        executionRepository.findActiveByIdForUpdate(task.executionId())
            .orElse(null);
    Instant now = Instant.now();
    if (execution == null
        || execution.getStatus() != AgentExecutionStatus.RUNNING
        || execution.getLeaseToken() != task.leaseToken()
        || !task.workerId().equals(execution.getLeaseOwner())
        || execution.getLeaseExpiresAt() == null
        || !now.isBefore(execution.getLeaseExpiresAt())) {
      return false;
    }
    execution.setLeaseExpiresAt(now.plus(leaseDuration()));
    executionRepository.save(execution);
    return true;
  }

  /**
   * Starts one model trace and reserves one Agent reasoning step.
   */
  @Transactional(rollbackFor = Exception.class)
  public String beginModel(
      final ExecutionTask task,
      final String modelDefinitionId,
      final String requestJson,
      final String requestHash
  ) {
    AiAgentExecution execution = requireOwned(task);
    Instant now = Instant.now();
    if (stopAtCheckpoint(execution, now)) {
      executionRepository.save(execution);
      return null;
    }
    if (execution.getStepCount() >= execution.getMaximumSteps()) {
      failExecution(
          execution,
          now,
          "AGENT_BUDGET_STEPS_EXCEEDED",
          "Agent maximum step budget was exhausted"
      );
      executionRepository.save(execution);
      return null;
    }
    execution.setStepCount(execution.getStepCount() + 1);
    execution.setCurrentModelId(modelDefinitionId);
    execution.setLeaseExpiresAt(now.plus(leaseDuration()));
    AiAgentExecutionTrace trace = new AiAgentExecutionTrace();
    trace.setExecutionId(execution.getId());
    trace.setSequence(nextSequence(execution.getId()));
    trace.setType(AgentTraceType.MODEL);
    trace.setStatus(AgentTraceStatus.RUNNING);
    trace.setModelDefinitionId(modelDefinitionId);
    trace.setRequestHash(requestHash);
    trace.setInputJson(requestJson);
    trace.setStartedAt(now);
    trace = traceRepository.save(trace);
    execution.setCurrentTraceId(trace.getId());
    executionRepository.save(execution);
    publish(
        execution,
        AgentExecutionEventType.MODEL_STARTED,
        trace.getId(),
        null,
        Map.of("modelDefinitionId", modelDefinitionId),
        now
    );
    return trace.getId();
  }

  /**
   * Pins long-term retrieval before the first model invocation.
   */
  @Transactional(rollbackFor = Exception.class)
  public void checkpointLongTermMemory(
      final ExecutionTask task,
      final AiAgentMemoryContext context
  ) {
    AiAgentExecution execution = requireOwned(task);
    if (execution.getLongTermMemoryContextJson() != null) {
      if (!java.util.Objects.equals(
          execution.getLongTermMemorySnapshotHash(),
          context.snapshotHash()
      )) {
        throw new IllegalStateException(
            "Agent long-term memory snapshot changed after it was pinned"
        );
      }
      return;
    }
    execution.setLongTermMemoryContextJson(context.contextJson());
    execution.setLongTermMemoryRetrievedCount(context.memories().size());
    execution.setLongTermMemoryInjectedCharacters(
        context.injectedCharacters()
    );
    execution.setLongTermMemorySnapshotHash(context.snapshotHash());
    execution.setLongTermMemoryRetrievedAt(Instant.now());
    execution.setLeaseExpiresAt(Instant.now().plus(leaseDuration()));
    executionRepository.save(execution);
    publish(
        execution,
        AgentExecutionEventType.MEMORY_RETRIEVED,
        execution.getCurrentTraceId(),
        null,
        Map.of(
            "retrievedCount", context.memories().size(),
            "injectedCharacters", context.injectedCharacters(),
            "snapshotHash", context.snapshotHash()
        ),
        execution.getLongTermMemoryRetrievedAt()
    );
  }

  /**
   * Records a failed model attempt while retaining the execution lease.
   */
  @Transactional(rollbackFor = Exception.class)
  public boolean failModelAttempt(
      final ExecutionTask task,
      final String traceId,
      final String errorCode,
      final String errorMessage
  ) {
    AiAgentExecution execution = requireOwned(task);
    AiAgentExecutionTrace trace = requireTrace(execution, traceId);
    trace.setStatus(AgentTraceStatus.FAILED);
    trace.setCompletedAt(Instant.now());
    applyTraceDiagnostic(trace, errorCode);
    traceRepository.save(trace);
    publish(
        execution,
        AgentExecutionEventType.MODEL_FAILED,
        trace.getId(),
        null,
        payloadError(errorCode),
        trace.getCompletedAt()
    );
    execution.setCurrentTraceId(null);
    execution.setCurrentModelId(null);
    Instant now = Instant.now();
    if (stopAtCheckpoint(execution, now)) {
      executionRepository.save(execution);
      return false;
    }
    execution.setLeaseExpiresAt(now.plus(leaseDuration()));
    executionRepository.save(execution);
    return true;
  }

  /**
   * Stores a successful model response and budget consumption.
   */
  @Transactional(rollbackFor = Exception.class)
  public boolean completeModel(
      final ExecutionTask task,
      final ModelCheckpoint checkpoint
  ) {
    AiAgentExecution execution = requireOwned(task);
    AiAgentExecutionTrace trace = requireTrace(
        execution,
        checkpoint.traceId()
    );
    trace.setStatus(AgentTraceStatus.SUCCEEDED);
    trace.setModelInvocationId(checkpoint.invocationId());
    trace.setResponseHash(checkpoint.responseHash());
    trace.setOutputJson(checkpoint.responseJson());
    trace.setInputTokens(checkpoint.inputTokens());
    trace.setOutputTokens(checkpoint.outputTokens());
    trace.setCost(nonNegative(checkpoint.cost()));
    trace.setCompletedAt(Instant.now());
    traceRepository.save(trace);
    publish(
        execution,
        AgentExecutionEventType.MODEL_SUCCEEDED,
        trace.getId(),
        null,
        Map.of(
            "inputTokens", positive(checkpoint.inputTokens()),
            "outputTokens", positive(checkpoint.outputTokens())
        ),
        trace.getCompletedAt()
    );
    execution.setConsumedInputTokens(
        execution.getConsumedInputTokens() + positive(checkpoint.inputTokens())
    );
    execution.setConsumedOutputTokens(
        execution.getConsumedOutputTokens()
            + positive(checkpoint.outputTokens())
    );
    execution.setConsumedCost(
        nonNegative(execution.getConsumedCost()).add(
            nonNegative(checkpoint.cost())
        )
    );
    execution.setConversationJson(checkpoint.conversationJson());
    execution.setPendingSkillCallsJson(checkpoint.pendingSkillCallsJson());
    applyMemoryCheckpoint(
        execution,
        checkpoint.compactedMessages(),
        checkpoint.memorySummaryHash()
    );
    execution.setCurrentTraceId(null);
    execution.setCurrentModelId(checkpoint.modelDefinitionId());
    if (checkpoint.pendingSkillCallsJson() != null) {
      execution.setLoopDepth(execution.getLoopDepth() + 1);
    }
    String budgetError = budgetError(execution);
    if (budgetError != null) {
      failExecution(
          execution,
          Instant.now(),
          budgetError,
          "Agent token, loop, or cost budget was exhausted"
      );
      executionRepository.save(execution);
      return false;
    }
    if (stopAtCheckpoint(execution, Instant.now())) {
      executionRepository.save(execution);
      return false;
    }
    execution.setLeaseExpiresAt(Instant.now().plus(leaseDuration()));
    executionRepository.save(execution);
    return true;
  }

  /**
   * Atomically creates or reuses a pinned child Skill and checkpoints the
   * Agent wait. The child service joins this transaction so cancellation or
   * lease fencing cannot leave an unassociated child execution behind.
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = Exception.class
  )
  public boolean launchSkill(
      final ExecutionTask task,
      final SkillLaunchCheckpoint checkpoint
  ) {
    if (checkpoint == null) {
      throw new IllegalArgumentException(
          "Agent Skill launch checkpoint must not be null"
      );
    }
    AiAgentExecution execution = executionRepository
        .findActiveByIdForUpdate(task.executionId())
        .orElseThrow(() -> new IllegalStateException(
            "Agent execution no longer exists"
        ));
    if (isSameSkillWait(execution, task, checkpoint)) {
      return true;
    }
    requireOwned(task, execution);
    if (stopAtCheckpoint(execution, Instant.now())) {
      executionRepository.save(execution);
      return false;
    }
    if (execution.getCurrentSkillExecutionId() != null
        || execution.getCurrentSkillBindingId() != null
        || execution.getCurrentToolCallId() != null) {
      throw new IllegalStateException(
          "Agent execution already has an active child Skill"
      );
    }
    AiSkillExecution child = skillExecutionService.startVersionForAgent(
        new SkillAgentExecutionCommand(
            checkpoint.skillId(),
            checkpoint.skillVersionId(),
            checkpoint.expectedContentHash(),
            execution.getScopeType(),
            execution.getTenantId(),
            execution.getRequestedBy(),
            execution.getId() + ":" + checkpoint.toolCallId(),
            checkpoint.input()
        )
    );
    assertPinnedChild(execution, checkpoint, child);
    AiAgentExecutionTrace trace = new AiAgentExecutionTrace();
    trace.setExecutionId(execution.getId());
    trace.setSequence(nextSequence(execution.getId()));
    trace.setType(AgentTraceType.SKILL);
    trace.setStatus(AgentTraceStatus.RUNNING);
    trace.setSkillBindingId(checkpoint.skillBindingId());
    trace.setSkillId(checkpoint.skillId());
    trace.setSkillVersionId(checkpoint.skillVersionId());
    trace.setSkillExecutionId(child.getId());
    trace.setCapabilityAlias(checkpoint.capabilityAlias());
    trace.setToolCallId(checkpoint.toolCallId());
    trace.setRequestHash(checkpoint.requestHash());
    trace.setInputJson(checkpoint.inputJson());
    trace.setStartedAt(Instant.now());
    trace = traceRepository.save(trace);
    execution.setCurrentSkillBindingId(checkpoint.skillBindingId());
    execution.setCurrentSkillExecutionId(child.getId());
    execution.setCurrentToolCallId(checkpoint.toolCallId());
    execution.setCurrentTraceId(trace.getId());
    Instant now = Instant.now();
    execution.setStatus(AgentExecutionStatus.WAITING_SKILL);
    execution.setNextPollAt(now.plus(skillPollInterval()));
    clearLease(execution);
    executionRepository.save(execution);
    publish(
        execution,
        AgentExecutionEventType.SKILL_STARTED,
        trace.getId(),
        null,
        Map.of(
            "capabilityAlias", checkpoint.capabilityAlias(),
            "skillExecutionId", child.getId()
        ),
        trace.getStartedAt()
    );
    return true;
  }

  private boolean isSameSkillWait(
      final AiAgentExecution execution,
      final ExecutionTask task,
      final SkillLaunchCheckpoint checkpoint
  ) {
    if (execution.getStatus() != AgentExecutionStatus.WAITING_SKILL
        || execution.getLeaseToken() != task.leaseToken()
        || execution.getCurrentTraceId() == null
        || execution.getCurrentSkillExecutionId() == null
        || !java.util.Objects.equals(
            execution.getCurrentSkillBindingId(),
            checkpoint.skillBindingId()
        )
        || !java.util.Objects.equals(
            execution.getCurrentToolCallId(),
            checkpoint.toolCallId()
        )) {
      return false;
    }
    return traceRepository.findActiveById(execution.getCurrentTraceId())
        .filter(trace -> trace.getType() == AgentTraceType.SKILL)
        .filter(trace -> trace.getStatus() == AgentTraceStatus.RUNNING)
        .filter(trace -> execution.getId().equals(trace.getExecutionId()))
        .filter(trace -> checkpoint.skillBindingId().equals(
            trace.getSkillBindingId()
        ))
        .filter(trace -> checkpoint.skillId().equals(trace.getSkillId()))
        .filter(trace -> checkpoint.skillVersionId().equals(
            trace.getSkillVersionId()
        ))
        .filter(trace -> checkpoint.capabilityAlias().equals(
            trace.getCapabilityAlias()
        ))
        .filter(trace -> checkpoint.toolCallId().equals(
            trace.getToolCallId()
        ))
        .filter(trace -> checkpoint.requestHash().equals(
            trace.getRequestHash()
        ))
        .filter(trace -> java.util.Objects.equals(
            checkpoint.inputJson(),
            trace.getInputJson()
        ))
        .filter(trace -> execution.getCurrentSkillExecutionId().equals(
            trace.getSkillExecutionId()
        ))
        .isPresent();
  }

  private static void assertPinnedChild(
      final AiAgentExecution execution,
      final SkillLaunchCheckpoint checkpoint,
      final AiSkillExecution child
  ) {
    if (child == null || child.getId() == null || child.getId().isBlank()
        || !checkpoint.skillId().equals(child.getSkillId())
        || !checkpoint.skillVersionId().equals(child.getSkillVersionId())
        || execution.getScopeType() != child.getScopeType()
        || !java.util.Objects.equals(
            execution.getTenantId(),
            child.getTenantId()
        )) {
      throw new IllegalStateException(
          "Pinned child Skill escaped its Agent execution boundary"
      );
    }
  }

  /**
   * Keeps a non-terminal child Skill execution in a bounded polling state.
   */
  @Transactional(rollbackFor = Exception.class)
  public void continueWaitingForSkill(final ExecutionTask task) {
    AiAgentExecution execution = requireOwned(task);
    Instant now = Instant.now();
    if (pauseAtCheckpoint(execution, now)) {
      executionRepository.save(execution);
      return;
    }
    execution.setStatus(AgentExecutionStatus.WAITING_SKILL);
    execution.setNextPollAt(now.plus(skillPollInterval()));
    clearLease(execution);
    executionRepository.save(execution);
  }

  /**
   * Completes a child Skill trace and checkpoints the model Tool result.
   */
  @Transactional(rollbackFor = Exception.class)
  public boolean completeSkill(
      final ExecutionTask task,
      final SkillResultCheckpoint checkpoint
  ) {
    AiAgentExecution execution = requireOwned(task);
    AiAgentExecutionTrace trace = requireTrace(
        execution,
        execution.getCurrentTraceId()
    );
    trace.setStatus(AgentTraceStatus.SUCCEEDED);
    trace.setResponseHash(checkpoint.responseHash());
    trace.setOutputJson(checkpoint.outputJson());
    trace.setCompletedAt(Instant.now());
    traceRepository.save(trace);
    publish(
        execution,
        AgentExecutionEventType.SKILL_SUCCEEDED,
        trace.getId(),
        null,
        Map.of(
            "capabilityAlias", trace.getCapabilityAlias(),
            "skillExecutionId", trace.getSkillExecutionId()
        ),
        trace.getCompletedAt()
    );
    execution.setConversationJson(checkpoint.conversationJson());
    execution.setPendingSkillCallsJson(checkpoint.pendingSkillCallsJson());
    applyMemoryCheckpoint(
        execution,
        checkpoint.compactedMessages(),
        checkpoint.memorySummaryHash()
    );
    execution.setCurrentSkillBindingId(null);
    execution.setCurrentSkillExecutionId(null);
    execution.setCurrentToolCallId(null);
    execution.setCurrentTraceId(null);
    Instant now = Instant.now();
    boolean stopped = stopAtCheckpoint(execution, now);
    if (!stopped) {
      execution.setStatus(AgentExecutionStatus.RUNNING);
      execution.setLeaseExpiresAt(now.plus(leaseDuration()));
    }
    executionRepository.save(execution);
    return !stopped;
  }

  /**
   * Rejects invalid Skill arguments before a child execution is created and
   * checkpoints a retryable Tool result for the next model turn.
   */
  @Transactional(rollbackFor = Exception.class)
  public boolean rejectSkillArguments(
      final ExecutionTask task,
      final SkillFailureCheckpoint checkpoint
  ) {
    AiAgentExecution execution = requireOwned(task);
    AiAgentExecutionTrace trace = new AiAgentExecutionTrace();
    trace.setExecutionId(execution.getId());
    trace.setSequence(nextSequence(execution.getId()));
    trace.setType(AgentTraceType.SKILL);
    trace.setStatus(AgentTraceStatus.FAILED);
    trace.setSkillBindingId(checkpoint.skillBindingId());
    trace.setSkillId(checkpoint.skillId());
    trace.setSkillVersionId(checkpoint.skillVersionId());
    trace.setCapabilityAlias(checkpoint.capabilityAlias());
    trace.setToolCallId(checkpoint.toolCallId());
    trace.setRequestHash(checkpoint.requestHash());
    trace.setInputJson(checkpoint.inputJson());
    String safeOutputJson = redactDiagnostic(
        checkpoint.outputJson(),
        checkpoint.errorMessage(),
        AiExecutionDiagnostics.agentTrace(
            checkpoint.errorCode()
        ).errorMessage()
    );
    trace.setResponseHash(java.util.Objects.equals(
        safeOutputJson,
        checkpoint.outputJson()
    ) ? checkpoint.responseHash() : null);
    trace.setOutputJson(safeOutputJson);
    applyTraceDiagnostic(trace, checkpoint.errorCode());
    trace.setStartedAt(Instant.now());
    trace.setCompletedAt(trace.getStartedAt());
    trace = traceRepository.save(trace);
    publish(
        execution,
        AgentExecutionEventType.SKILL_FAILED,
        trace.getId(),
        null,
        payloadError(checkpoint.errorCode()),
        trace.getCompletedAt()
    );
    String safeConversationJson = redactDiagnostic(
        checkpoint.conversationJson(),
        checkpoint.errorMessage(),
        trace.getErrorMessage()
    );
    execution.setConversationJson(safeConversationJson);
    execution.setPendingSkillCallsJson(checkpoint.pendingSkillCallsJson());
    applyMemoryCheckpoint(
        execution,
        checkpoint.compactedMessages(),
        java.util.Objects.equals(
            safeConversationJson,
            checkpoint.conversationJson()
        ) ? checkpoint.memorySummaryHash() : null
    );
    Instant now = Instant.now();
    boolean stopped = stopAtCheckpoint(execution, now);
    if (!stopped) {
      execution.setStatus(AgentExecutionStatus.RUNNING);
      execution.setLeaseExpiresAt(now.plus(leaseDuration()));
    }
    executionRepository.save(execution);
    return !stopped;
  }

  /**
   * Fails a child Skill trace and the owning Agent execution.
   */
  @Transactional(rollbackFor = Exception.class)
  public void failSkill(
      final ExecutionTask task,
      final String errorCode,
      final String errorMessage
  ) {
    AiAgentExecution execution = requireOwned(task);
    if (execution.getCurrentTraceId() != null) {
      AiAgentExecutionTrace trace = requireTrace(
          execution,
          execution.getCurrentTraceId()
      );
      trace.setStatus(AgentTraceStatus.FAILED);
      trace.setCompletedAt(Instant.now());
      applyTraceDiagnostic(trace, errorCode);
      traceRepository.save(trace);
      publish(
          execution,
          AgentExecutionEventType.SKILL_FAILED,
          trace.getId(),
          null,
          payloadError(errorCode),
          trace.getCompletedAt()
      );
    }
    failExecution(execution, Instant.now(), errorCode, errorMessage);
    executionRepository.save(execution);
  }

  /**
   * Completes the Agent with its validated output.
   */
  @Transactional(rollbackFor = Exception.class)
  public boolean succeed(
      final ExecutionTask task,
      final String outputJson,
      final AiAgentMemoryWrite memory
  ) {
    AiAgentExecution execution = requireOwned(task);
    if (stopAtCheckpoint(execution, Instant.now())) {
      executionRepository.save(execution);
      return false;
    }
    if (memory != null) {
      assertMemoryWriteMatches(execution, memory);
      String memoryId = memoryRepository.storeAndPrune(memory);
      execution.setLongTermMemoryWrittenId(memoryId);
      execution.setLongTermMemoryWrittenAt(Instant.now());
      publish(
          execution,
          AgentExecutionEventType.MEMORY_WRITTEN,
          null,
          null,
          Map.of("memoryId", memoryId),
          execution.getLongTermMemoryWrittenAt()
      );
    }
    execution.setOutputJson(outputJson);
    execution.setStatus(AgentExecutionStatus.SUCCEEDED);
    execution.setCompletedAt(Instant.now());
    execution.setPauseRequested(false);
    clearLease(execution);
    executionRepository.save(execution);
    publish(
        execution,
        AgentExecutionEventType.EXECUTION_SUCCEEDED,
        null,
        null,
        Map.of(),
        execution.getCompletedAt()
    );
    return true;
  }

  private static void assertMemoryWriteMatches(
      final AiAgentExecution execution,
      final AiAgentMemoryWrite memory
  ) {
    if (!execution.getId().equals(memory.sourceExecutionId())
        || !execution.getAgentId().equals(memory.agentId())
        || !execution.getAgentVersionId().equals(memory.agentVersionId())
        || execution.getScopeType() != memory.scopeType()
        || !java.util.Objects.equals(
            execution.getTenantId(),
            memory.tenantId()
        )
        || !java.util.Objects.equals(
            execution.getRequestedBy(),
            memory.subjectId()
        )) {
      throw new IllegalStateException(
          "Agent long-term memory write escaped its execution boundary"
      );
    }
  }

  /**
   * Fails an Agent while retaining prior trace checkpoints.
   */
  @Transactional(rollbackFor = Exception.class)
  public void fail(
      final ExecutionTask task,
      final String errorCode,
      final String errorMessage
  ) {
    AiAgentExecution execution = requireOwned(task);
    failExecution(execution, Instant.now(), errorCode, errorMessage);
    executionRepository.save(execution);
  }

  private AiAgentExecution requireOwned(final ExecutionTask task) {
    AiAgentExecution execution =
        executionRepository.findActiveByIdForUpdate(task.executionId())
            .orElseThrow(() -> new IllegalStateException(
                "Agent execution no longer exists"
            ));
    requireOwned(task, execution);
    return execution;
  }

  private static void requireOwned(
      final ExecutionTask task,
      final AiAgentExecution execution
  ) {
    if (execution.getStatus() != AgentExecutionStatus.RUNNING
        || execution.getLeaseToken() != task.leaseToken()
        || !task.workerId().equals(execution.getLeaseOwner())
        || execution.getLeaseExpiresAt() == null
        || !Instant.now().isBefore(execution.getLeaseExpiresAt())) {
      throw new IllegalStateException(
          "Agent execution lease is no longer owned by this worker"
      );
    }
  }

  private AiAgentExecutionTrace requireTrace(
      final AiAgentExecution execution,
      final String traceId
  ) {
    return traceRepository.findActiveById(traceId)
        .filter(trace -> execution.getId().equals(trace.getExecutionId()))
        .orElseThrow(() -> new IllegalStateException(
            "Agent execution trace no longer exists"
        ));
  }

  private int nextSequence(final String executionId) {
    long count = traceRepository.countActiveByExecutionId(executionId);
    if (count >= Integer.MAX_VALUE) {
      throw new IllegalStateException("Agent execution has too many traces");
    }
    return (int) count;
  }

  private String budgetError(final AiAgentExecution execution) {
    if (execution.getConsumedInputTokens()
        > execution.getMaximumInputTokens()) {
      return "AGENT_BUDGET_INPUT_TOKENS_EXCEEDED";
    }
    if (execution.getConsumedOutputTokens()
        > execution.getMaximumOutputTokens()) {
      return "AGENT_BUDGET_OUTPUT_TOKENS_EXCEEDED";
    }
    if (execution.getLoopDepth() > execution.getMaximumLoopDepth()) {
      return "AGENT_BUDGET_LOOP_DEPTH_EXCEEDED";
    }
    if (nonNegative(execution.getConsumedCost()).compareTo(
        nonNegative(execution.getMaximumCost())
    ) > 0) {
      return "AGENT_BUDGET_COST_EXCEEDED";
    }
    return null;
  }

  private void failExecution(
      final AiAgentExecution execution,
      final Instant now,
      final String errorCode,
      final String errorMessage
  ) {
    execution.setStatus(AgentExecutionStatus.FAILED);
    execution.setCompletedAt(now);
    applyExecutionDiagnostic(execution, errorCode);
    execution.setPauseRequested(false);
    cancelHumanIntervention(
        execution,
        "Agent execution failed before human intervention completed"
    );
    clearLease(execution);
    publish(
        execution,
        AgentExecutionEventType.EXECUTION_FAILED,
        execution.getCurrentTraceId(),
        null,
        payloadError(errorCode),
        now
    );
  }

  private static void applyMemoryCheckpoint(
      final AiAgentExecution execution,
      final int compactedMessages,
      final String memorySummaryHash
  ) {
    execution.setMemorySummaryHash(memorySummaryHash);
    if (compactedMessages <= 0) {
      return;
    }
    execution.setCompactedMessageCount(
        positive(execution.getCompactedMessageCount()) + compactedMessages
    );
    execution.setMemoryRevision(
        positive(execution.getMemoryRevision()) + 1
    );
    execution.setLastMemoryCompactedAt(Instant.now());
  }

  private boolean stopAtCheckpoint(
      final AiAgentExecution execution,
      final Instant now
  ) {
    return pauseAtCheckpoint(execution, now)
        || waitForHumanIntervention(execution, now);
  }

  private boolean waitForHumanIntervention(
      final AiAgentExecution execution,
      final Instant now
  ) {
    if (execution.getCurrentHumanInterventionId() == null) {
      return false;
    }
    AiAgentHumanIntervention intervention =
        requireHumanIntervention(execution);
    if (!now.isBefore(intervention.getExpiresAt())) {
      expireHumanIntervention(execution, now);
      return true;
    }
    if (intervention.getStatus()
        != AgentHumanInterventionStatus.REQUESTED
        && intervention.getStatus()
            != AgentHumanInterventionStatus.WAITING) {
      throw new IllegalStateException(
          "Agent human intervention checkpoint is corrupted"
      );
    }
    intervention.setStatus(AgentHumanInterventionStatus.WAITING);
    if (intervention.getWaitingAt() == null) {
      intervention.setWaitingAt(now);
    }
    interventionRepository.save(intervention);
    execution.setStatus(AgentExecutionStatus.WAITING_HUMAN);
    execution.setNextPollAt(intervention.getExpiresAt());
    clearLease(execution);
    publish(
        execution,
        AgentExecutionEventType.HUMAN_INTERVENTION_WAITING,
        execution.getCurrentTraceId(),
        intervention.getId(),
        Map.of("expiresAt", intervention.getExpiresAt().toString()),
        now
    );
    return true;
  }

  private void expireHumanIntervention(
      final AiAgentExecution execution,
      final Instant now
  ) {
    AiAgentHumanIntervention intervention =
        requireHumanIntervention(execution);
    if (now.isBefore(intervention.getExpiresAt())) {
      execution.setNextPollAt(intervention.getExpiresAt());
      return;
    }
    intervention.setStatus(AgentHumanInterventionStatus.EXPIRED);
    intervention.setRespondedAt(now);
    intervention.setCompletionReason("Human intervention timed out");
    interventionRepository.save(intervention);
    execution.setCompletedAt(now);
    execution.setPauseRequested(false);
    execution.setCurrentHumanInterventionId(null);
    if (execution.getHumanInterventionTimeoutAction()
        == AgentHumanInterventionTimeoutAction.CANCEL) {
      activeTraceCancellation.cancelActiveTrace(
          execution,
          "agent-runtime",
          "Agent human intervention timed out",
          now
      );
      execution.setStatus(AgentExecutionStatus.CANCELLED);
    } else {
      execution.setStatus(AgentExecutionStatus.FAILED);
      applyExecutionDiagnostic(
          execution,
          "AGENT_HUMAN_INTERVENTION_TIMEOUT"
      );
    }
    clearLease(execution);
    publish(
        execution,
        AgentExecutionEventType.HUMAN_INTERVENTION_EXPIRED,
        null,
        intervention.getId(),
        Map.of(),
        now
    );
    publish(
        execution,
        execution.getStatus() == AgentExecutionStatus.CANCELLED
            ? AgentExecutionEventType.EXECUTION_CANCELLED
            : AgentExecutionEventType.EXECUTION_FAILED,
        null,
        intervention.getId(),
        payloadError("AGENT_HUMAN_INTERVENTION_TIMEOUT"),
        now
    );
  }

  private AiAgentHumanIntervention requireHumanIntervention(
      final AiAgentExecution execution
  ) {
    AiAgentHumanIntervention intervention = interventionRepository
        .findActiveByIdForUpdate(execution.getCurrentHumanInterventionId())
        .orElseThrow(() -> new IllegalStateException(
            "Agent human intervention no longer exists"
        ));
    if (!execution.getId().equals(intervention.getExecutionId())
        || !execution.getAgentId().equals(intervention.getAgentId())
        || execution.getScopeType() != intervention.getScopeType()
        || !java.util.Objects.equals(
            execution.getTenantId(),
            intervention.getTenantId()
        )) {
      throw new IllegalStateException(
          "Agent human intervention escaped its execution boundary"
      );
    }
    return intervention;
  }

  private void cancelHumanIntervention(
      final AiAgentExecution execution,
      final String reason
  ) {
    if (execution.getCurrentHumanInterventionId() == null) {
      return;
    }
    AiAgentHumanIntervention intervention =
        requireHumanIntervention(execution);
    if (intervention.getStatus()
        == AgentHumanInterventionStatus.REQUESTED
        || intervention.getStatus()
            == AgentHumanInterventionStatus.WAITING) {
      intervention.setStatus(AgentHumanInterventionStatus.CANCELLED);
      intervention.setRespondedAt(Instant.now());
      intervention.setCompletionReason(reason);
      interventionRepository.save(intervention);
      publish(
          execution,
          AgentExecutionEventType.HUMAN_INTERVENTION_CANCELLED,
          execution.getCurrentTraceId(),
          intervention.getId(),
          Map.of("reason", reason),
          Instant.now()
      );
    }
    execution.setCurrentHumanInterventionId(null);
  }

  private boolean pauseAtCheckpoint(
      final AiAgentExecution execution,
      final Instant now
  ) {
    if (!Boolean.TRUE.equals(execution.getPauseRequested())) {
      return false;
    }
    execution.setStatus(AgentExecutionStatus.PAUSED);
    execution.setPausedAt(now);
    execution.setNextPollAt(null);
    clearLease(execution);
    publish(
        execution,
        AgentExecutionEventType.PAUSED,
        execution.getCurrentTraceId(),
        execution.getCurrentHumanInterventionId(),
        Map.of(),
        now
    );
    return true;
  }

  private void publish(
      final AiAgentExecution execution,
      final AgentExecutionEventType type,
      final String traceId,
      final String interventionId,
      final Map<String, Object> payload,
      final Instant occurredAt
  ) {
    eventPublisher.publish(
        execution,
        type,
        traceId,
        interventionId,
        null,
        payload,
        occurredAt
    );
  }

  private static Map<String, Object> payloadError(
      final String errorCode
  ) {
    if (errorCode == null || errorCode.isBlank()) {
      return Map.of();
    }
    return Map.of(
        "errorCode",
        AiExecutionDiagnostics.agentExecution(errorCode).errorCode()
    );
  }

  private static void applyExecutionDiagnostic(
      final AiAgentExecution execution,
      final String errorCode
  ) {
    StableDiagnostic diagnostic =
        AiExecutionDiagnostics.agentExecution(errorCode);
    execution.setErrorCode(diagnostic.errorCode());
    execution.setErrorMessage(diagnostic.errorMessage());
  }

  private static void applyTraceDiagnostic(
      final AiAgentExecutionTrace trace,
      final String errorCode
  ) {
    StableDiagnostic diagnostic =
        AiExecutionDiagnostics.agentTrace(errorCode);
    trace.setErrorCode(diagnostic.errorCode());
    trace.setErrorMessage(diagnostic.errorMessage());
  }

  private int batchSize() {
    Integer configured = properties.getBatchSize();
    return configured == null ? 4 : Math.max(1, Math.min(configured, 32));
  }

  private int maxAttempts() {
    Integer configured = properties.getMaxAttempts();
    return configured == null ? 8 : Math.max(1, Math.min(configured, 64));
  }

  private Duration leaseDuration() {
    Duration configured = properties.getLeaseDuration();
    return configured == null || configured.isNegative()
        || configured.isZero() ? Duration.ofMinutes(2) : configured;
  }

  private Duration skillPollInterval() {
    Duration configured = properties.getSkillPollInterval();
    return configured == null || configured.isNegative()
        || configured.isZero() ? Duration.ofMillis(500) : configured;
  }

  private static int positive(final Integer value) {
    return value == null ? 0 : Math.max(0, value);
  }

  private static BigDecimal nonNegative(final BigDecimal value) {
    return value == null || value.signum() < 0
        ? BigDecimal.ZERO : value;
  }

  private static String limit(final String value, final int maximum) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.replaceAll("\\s+", " ").trim();
    return normalized.length() <= maximum
        ? normalized : normalized.substring(0, maximum);
  }

  private static String redactDiagnostic(
      final String value,
      final String untrustedDiagnostic,
      final String stableCode
  ) {
    if (value == null || untrustedDiagnostic == null
        || untrustedDiagnostic.isBlank()
        || stableCode.equals(untrustedDiagnostic)) {
      return value;
    }
    String result = value;
    String untrusted = untrustedDiagnostic;
    String replacement = stableCode;
    for (int level = 0; level < 8; level++) {
      result = result.replace(untrusted, replacement);
      untrusted = jsonEscape(untrusted);
      replacement = jsonEscape(replacement);
    }
    return result;
  }

  private static String jsonEscape(final String value) {
    return value.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\b", "\\b")
        .replace("\f", "\\f")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  private static void clearLease(final AiAgentExecution execution) {
    execution.setLeaseOwner(null);
    execution.setLeaseExpiresAt(null);
  }

  /**
   * Stable fencing identity passed outside coordinator transactions.
   */
  public record ExecutionTask(
      String executionId,
      String workerId,
      long leaseToken
  ) {
  }

  /**
   * Successful model call checkpoint.
   */
  public record ModelCheckpoint(
      String traceId,
      String modelDefinitionId,
      String invocationId,
      String responseHash,
      String responseJson,
      String conversationJson,
      String pendingSkillCallsJson,
      Integer inputTokens,
      Integer outputTokens,
      BigDecimal cost,
      int compactedMessages,
      String memorySummaryHash
  ) {
  }

  /**
   * Pinned child Skill submission checkpoint.
   */
  public record SkillLaunchCheckpoint(
      String skillBindingId,
      String skillId,
      String skillVersionId,
      String expectedContentHash,
      String capabilityAlias,
      String toolCallId,
      String requestHash,
      String inputJson,
      Map<String, Object> input
  ) {
  }

  /**
   * Successful child Skill result checkpoint.
   */
  public record SkillResultCheckpoint(
      String responseHash,
      String outputJson,
      String conversationJson,
      String pendingSkillCallsJson,
      int compactedMessages,
      String memorySummaryHash
  ) {
  }

  /**
   * Retryable pre-execution Skill argument rejection checkpoint.
   */
  public record SkillFailureCheckpoint(
      String skillBindingId,
      String skillId,
      String skillVersionId,
      String capabilityAlias,
      String toolCallId,
      String requestHash,
      String inputJson,
      String errorCode,
      String errorMessage,
      String responseHash,
      String outputJson,
      String conversationJson,
      String pendingSkillCallsJson,
      int compactedMessages,
      String memorySummaryHash
  ) {
  }
}
