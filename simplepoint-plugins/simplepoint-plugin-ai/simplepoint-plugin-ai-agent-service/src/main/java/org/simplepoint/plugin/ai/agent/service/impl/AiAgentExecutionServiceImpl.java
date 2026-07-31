package org.simplepoint.plugin.ai.agent.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentDefinition;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecution;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecutionEvent;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecutionTrace;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentHumanIntervention;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentVersion;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionDecisionRequest;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionEventFeed;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionEventType;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionMetrics;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionPauseRequest;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionStartRequest;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionStatusMetric;
import org.simplepoint.plugin.ai.agent.api.model.AgentHumanInterventionOutcome;
import org.simplepoint.plugin.ai.agent.api.model.AgentHumanInterventionRequest;
import org.simplepoint.plugin.ai.agent.api.model.AgentHumanInterventionResponseRequest;
import org.simplepoint.plugin.ai.agent.api.model.AgentHumanInterventionStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentHumanInterventionTimeoutAction;
import org.simplepoint.plugin.ai.agent.api.model.AgentMemoryScope;
import org.simplepoint.plugin.ai.agent.api.model.AgentStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceMetric;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceType;
import org.simplepoint.plugin.ai.agent.api.model.AgentVersionStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentWorkflowExecutionCommand;
import org.simplepoint.plugin.ai.agent.api.properties.AgentExecutionProperties;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentDefinitionRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentExecutionEventRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentExecutionRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentExecutionTraceRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentHumanInterventionRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentVersionRepository;
import org.simplepoint.plugin.ai.agent.api.service.AiAgentExecutionService;
import org.simplepoint.plugin.ai.agent.service.execution.AiAgentExecutionEventPublisher;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ContentBlock;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ContentType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.Message;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.MessageRole;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scope-aware control plane for durable Agent executions.
 */
@Service
public class AiAgentExecutionServiceImpl implements AiAgentExecutionService {

  private static final TypeReference<Map<String, Object>> MAP_TYPE =
      new TypeReference<>() {
      };

  private static final TypeReference<List<Message>> MESSAGE_LIST =
      new TypeReference<>() {
      };

  private final AiAgentDefinitionRepository agentRepository;

  private final AiAgentVersionRepository versionRepository;

  private final AiAgentExecutionRepository executionRepository;

  private final AiAgentExecutionTraceRepository traceRepository;

  private final AiAgentExecutionEventRepository eventRepository;

  private final AiAgentHumanInterventionRepository interventionRepository;

  private final AiAgentExecutionEventPublisher eventPublisher;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  private final SkillJsonSchemaValidator schemaValidator;

  private final AgentExecutionProperties properties;

  private final ObjectMapper objectMapper;

  private final ObjectMapper canonicalMapper;

  /**
   * Creates the Agent execution control plane.
   */
  public AiAgentExecutionServiceImpl(
      final AiAgentDefinitionRepository agentRepository,
      final AiAgentVersionRepository versionRepository,
      final AiAgentExecutionRepository executionRepository,
      final AiAgentExecutionTraceRepository traceRepository,
      final AiAgentExecutionEventRepository eventRepository,
      final AiAgentHumanInterventionRepository interventionRepository,
      final AiAgentExecutionEventPublisher eventPublisher,
      final AiScopeAccessPolicy scopeAccessPolicy,
      final SkillJsonSchemaValidator schemaValidator,
      final AgentExecutionProperties properties,
      final ObjectMapper objectMapper
  ) {
    this.agentRepository = agentRepository;
    this.versionRepository = versionRepository;
    this.executionRepository = executionRepository;
    this.traceRepository = traceRepository;
    this.eventRepository = eventRepository;
    this.interventionRepository = interventionRepository;
    this.eventPublisher = eventPublisher;
    this.scopeAccessPolicy = scopeAccessPolicy;
    this.schemaValidator = schemaValidator;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.canonicalMapper = objectMapper.copy()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiAgentExecution start(
      final String agentId,
      final AgentExecutionStartRequest request
  ) {
    if (request == null) {
      throw new IllegalArgumentException(
          "Agent execution request must not be null"
      );
    }
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiAgentDefinition agent = requireAgent(agentId, scope);
    if (!Boolean.TRUE.equals(agent.getEnabled())
        || agent.getStatus() != AgentStatus.ACTIVE
        || agent.getActiveVersionId() == null) {
      throw new IllegalStateException(
          "Agent has no active published version"
      );
    }
    AiAgentVersion version = versionRepository.findActiveById(
        agent.getActiveVersionId()
    ).filter(candidate -> agent.getId().equals(candidate.getAgentId()))
        .orElseThrow(() -> new IllegalStateException(
            "Active Agent version does not exist"
        ));
    if (version.getStatus() != AgentVersionStatus.PUBLISHED) {
      throw new IllegalStateException(
          "Active Agent version is not published"
      );
    }
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    return startVersion(
        agent,
        version,
        scope,
        context == null ? null : context.getUserId(),
        context == null ? null : context.getContextId(),
        request.idempotencyKey(),
        request.input()
    );
  }

  @Override
  @Transactional(
      propagation = Propagation.REQUIRES_NEW,
      rollbackFor = Exception.class
  )
  public AiAgentExecution startVersionForWorkflow(
      final AgentWorkflowExecutionCommand command
  ) {
    if (command == null || command.executionScope() == null) {
      throw new IllegalArgumentException(
          "Workflow Agent execution command must not be null"
      );
    }
    ScopeAssignment scope = new ScopeAssignment(
        command.executionScope(),
        command.tenantId()
    );
    AiAgentDefinition agent = agentRepository.findActiveById(
        required(command.agentId(), "Agent ID", 64)
    ).orElseThrow(() -> new IllegalArgumentException(
        "Pinned Agent does not exist"
    ));
    if (!scopeAccessPolicy.canUseResourceFromScope(
        agent.getScopeType(),
        agent.getTenantId(),
        scope.scopeType(),
        scope.tenantId()
    ) || !Boolean.TRUE.equals(agent.getEnabled())) {
      throw new IllegalArgumentException(
          "Pinned Agent is unavailable to the Workflow scope"
      );
    }
    AiAgentVersion version = versionRepository
        .findActiveByIdAndAgentId(
            required(command.agentVersionId(), "Agent version ID", 64),
            agent.getId()
        ).orElseThrow(() -> new IllegalArgumentException(
            "Pinned Agent version does not exist"
        ));
    if (version.getStatus() != AgentVersionStatus.PUBLISHED) {
      throw new IllegalStateException(
          "Pinned Agent version is not published"
      );
    }
    if (!required(
        command.expectedContentHash(),
        "Agent version content hash",
        64
    ).equals(version.getContentHash())) {
      throw new IllegalStateException(
          "Pinned Agent version content hash changed"
      );
    }
    return startVersion(
        agent,
        version,
        scope,
        command.requestedBy(),
        command.requestContextId(),
        command.idempotencyKey(),
        command.input()
    );
  }

  private AiAgentExecution startVersion(
      final AiAgentDefinition agent,
      final AiAgentVersion version,
      final ScopeAssignment scope,
      final String requestedBy,
      final String requestContextId,
      final String rawIdempotencyKey,
      final Map<String, Object> rawInput
  ) {
    String idempotencyHash = sha256(required(
        rawIdempotencyKey,
        "Agent execution idempotency key",
        128
    ));
    Map<String, Object> input = rawInput == null
        ? Map.of() : new LinkedHashMap<>(rawInput);
    String inputJson = writeJson(input);
    assertPayloadSize(inputJson, "Agent execution input");
    Map<String, Object> manifest = readMap(
        version.getManifestJson(),
        "Agent Manifest"
    );
    Map<String, Object> spec = map(manifest.get("spec"), "Agent spec");
    schemaValidator.validate(
        map(spec.get("inputSchema"), "Agent input Schema"),
        input,
        "Agent input"
    );
    Optional<AiAgentExecution> existing =
        executionRepository.findActiveByIdempotency(
            agent.getId(),
            scope.scopeType(),
            scope.tenantId(),
            idempotencyHash
        );
    if (existing.isPresent()) {
      AiAgentExecution execution = existing.orElseThrow();
      if (!sha256(inputJson).equals(execution.getInputHash())) {
        throw new IllegalArgumentException(
            "Idempotency key was already used with different input"
        );
      }
      return decorate(execution);
    }

    Map<String, Object> budgets = map(spec.get("budgets"), "Agent budgets");
    Map<String, Object> memory = map(spec.get("memory"), "Agent memory");
    Map<String, Object> approvals = optionalMap(spec.get("approvals"));
    Map<String, Object> executionApproval =
        optionalMap(approvals.get("execution"));
    final Map<String, Object> humanIntervention =
        optionalMap(spec.get("humanIntervention"));
    boolean approvalRequired = bool(
        executionApproval.get("required"),
        false
    );
    final boolean selfApprovalAllowed = bool(
        executionApproval.get("allowSelfApproval"),
        false
    );
    final Instant submittedAt = Instant.now();
    AiAgentExecution execution = new AiAgentExecution();
    execution.setAgentId(agent.getId());
    execution.setAgentVersionId(version.getId());
    execution.setAgentVersionContentHash(version.getContentHash());
    execution.setScopeType(scope.scopeType());
    execution.setTenantId(scope.tenantId());
    execution.setRequestedBy(requestedBy);
    execution.setRequestContextId(requestContextId);
    execution.setIdempotencyKeyHash(idempotencyHash);
    execution.setInputHash(sha256(inputJson));
    execution.setInputJson(inputJson);
    execution.setConversationJson(writeJson(List.of(userMessage(inputJson))));
    execution.setStatus(approvalRequired
        ? AgentExecutionStatus.WAITING_APPROVAL
        : AgentExecutionStatus.PENDING);
    execution.setPrimaryModelId(version.getPrimaryModelId());
    execution.setStepCount(0);
    execution.setLoopDepth(0);
    execution.setMaximumSteps(integer(
        budgets.get("maximumSteps"),
        16
    ));
    execution.setMaximumLoopDepth(integer(
        budgets.get("maximumLoopDepth"),
        4
    ));
    execution.setMaximumConcurrency(integer(
        budgets.get("maximumConcurrency"),
        4
    ));
    execution.setMaximumInputTokens(integer(
        budgets.get("maximumInputTokens"),
        32_000
    ));
    execution.setMaximumOutputTokens(integer(
        budgets.get("maximumOutputTokens"),
        4_096
    ));
    execution.setMaximumCost(decimal(budgets.get("maximumCost"), "10"));
    execution.setConsumedInputTokens(0);
    execution.setConsumedOutputTokens(0);
    execution.setConsumedCost(BigDecimal.ZERO);
    execution.setShortTermMemoryEnabled(bool(
        memory.get("shortTermEnabled"),
        true
    ));
    execution.setLongTermMemoryEnabled(bool(
        memory.get("longTermEnabled"),
        false
    ));
    execution.setLongTermMemoryScope(memoryScope(
        memory.get("longTermScope")
    ));
    execution.setMaximumMemoryMessages(integer(
        memory.get("maximumMessages"),
        20
    ));
    execution.setMaximumMemorySummaryCharacters(integer(
        memory.get("maximumSummaryCharacters"),
        8_192
    ));
    execution.setCompactedMessageCount(0);
    execution.setMemoryRevision(0);
    execution.setMaximumLongTermMemoryEntries(integer(
        memory.get("maximumLongTermEntries"),
        500
    ));
    execution.setLongTermMemoryRetrievalTopK(integer(
        memory.get("longTermRetrievalTopK"),
        5
    ));
    execution.setLongTermMemoryScoreThreshold(decimalValue(
        memory.get("longTermScoreThreshold"),
        0.05D
    ));
    execution.setMaximumLongTermMemoryInjectionCharacters(integer(
        memory.get("maximumLongTermInjectionCharacters"),
        6_000
    ));
    execution.setMaximumLongTermMemoryRecordCharacters(integer(
        memory.get("maximumLongTermRecordCharacters"),
        8_192
    ));
    execution.setLongTermMemoryRetentionDays(integer(
        memory.get("longTermRetentionDays"),
        365
    ));
    execution.setLongTermMemoryRetrievedCount(0);
    execution.setLongTermMemoryInjectedCharacters(0);
    if (Boolean.TRUE.equals(execution.getLongTermMemoryEnabled())
        && (execution.getRequestedBy() == null
            || execution.getRequestedBy().isBlank())) {
      throw new IllegalStateException(
          "Authenticated subject is required for long-term Agent memory"
      );
    }
    execution.setApprovalRequired(approvalRequired);
    execution.setSelfApprovalAllowed(selfApprovalAllowed);
    execution.setApprovalInstructions(text(
        executionApproval.get("instructions"),
        512
    ));
    execution.setApprovalRequestedAt(
        approvalRequired ? submittedAt : null
    );
    execution.setHumanInterventionEnabled(bool(
        humanIntervention.get("enabled"),
        false
    ));
    execution.setMaximumHumanInterventions(integer(
        humanIntervention.get("maximumRequests"),
        4
    ));
    execution.setHumanInterventionTimeoutSeconds(integer(
        humanIntervention.get("timeoutSeconds"),
        3_600
    ));
    execution.setHumanInterventionTimeoutAction(interventionTimeoutAction(
        humanIntervention.get("timeoutAction")
    ));
    execution.setHumanInterventionCount(0);
    execution.setPauseRequested(false);
    execution.setAttemptCount(0);
    execution.setLeaseToken(0);
    execution = executionRepository.save(execution);
    eventPublisher.publish(
        execution,
        AgentExecutionEventType.EXECUTION_CREATED,
        null,
        null,
        execution.getRequestedBy(),
        Map.of(),
        submittedAt
    );
    return decorate(execution);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AiAgentExecution> findAll(
      final String agentId,
      final Pageable pageable
  ) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiAgentDefinition agent = requireAgent(agentId, scope);
    return executionRepository.findAllActiveByAgentAndScope(
        agent.getId(),
        scope.scopeType(),
        scope.tenantId(),
        pageable
    ).map(this::decorateSummary);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AiAgentExecution> find(
      final String agentId,
      final String executionId
  ) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiAgentDefinition agent = requireAgent(agentId, scope);
    return executionRepository.findActiveById(required(
        executionId,
        "Agent execution ID",
        64
    )).filter(execution -> agent.getId().equals(execution.getAgentId()))
        .filter(execution -> execution.getScopeType() == scope.scopeType())
        .filter(execution -> java.util.Objects.equals(
            execution.getTenantId(),
            scope.tenantId()
        ))
        .map(this::decorate);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AiAgentExecutionTrace> findTraces(
      final String agentId,
      final String executionId,
      final AgentTraceType type,
      final AgentTraceStatus status,
      final Pageable pageable
  ) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    requireExecution(
        requireAgent(agentId, scope),
        scope,
        executionId
    );
    return traceRepository.findAllActiveByExecutionId(
        executionId,
        type,
        status,
        pageable
    ).map(this::decorateTrace);
  }

  @Override
  @Transactional(readOnly = true)
  public AgentExecutionEventFeed findEvents(
      final String agentId,
      final String executionId,
      final long afterSequence,
      final int limit
  ) {
    if (afterSequence < -1) {
      throw new IllegalArgumentException(
          "Agent event cursor must be at least -1"
      );
    }
    int boundedLimit = Math.max(1, Math.min(limit, 200));
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiAgentExecution execution = requireExecution(
        requireAgent(agentId, scope),
        scope,
        executionId
    );
    List<AiAgentExecutionEvent> fetched =
        eventRepository.findActiveAfterSequence(
            execution.getId(),
            afterSequence,
            PageRequest.of(0, boundedLimit + 1)
        );
    boolean hasMore = fetched.size() > boundedLimit;
    List<AiAgentExecutionEvent> events = fetched.stream()
        .limit(boundedLimit)
        .map(this::decorateEvent)
        .toList();
    long nextSequence = events.isEmpty()
        ? afterSequence : events.get(events.size() - 1).getSequence();
    return new AgentExecutionEventFeed(
        afterSequence,
        nextSequence,
        hasMore,
        execution.getStatus(),
        events
    );
  }

  @Override
  @Transactional(readOnly = true)
  public AgentExecutionMetrics metrics(
      final String agentId,
      final Instant from,
      final Instant to
  ) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiAgentDefinition agent = requireAgent(agentId, scope);
    Instant windowTo = to == null ? Instant.now() : to;
    Instant windowFrom = from == null
        ? windowTo.minus(Duration.ofHours(24)) : from;
    if (!windowFrom.isBefore(windowTo)) {
      throw new IllegalArgumentException(
          "Agent metrics window start must be before its end"
      );
    }
    if (Duration.between(windowFrom, windowTo).compareTo(
        Duration.ofDays(366)
    ) > 0) {
      throw new IllegalArgumentException(
          "Agent metrics window cannot exceed 366 days"
      );
    }
    List<AgentExecutionStatusMetric> statuses = executionRepository
        .aggregateByAgentAndWindow(
            agent.getId(),
            scope.scopeType(),
            scope.tenantId(),
            windowFrom,
            windowTo
        ).stream().map(AiAgentExecutionServiceImpl::normalize).toList();
    List<AgentTraceMetric> traces = traceRepository
        .aggregateByAgentAndWindow(
            agent.getId(),
            windowFrom,
            windowTo
        ).stream().map(AiAgentExecutionServiceImpl::normalize).toList();
    long total = statuses.stream()
        .mapToLong(AgentExecutionStatusMetric::executionCount).sum();
    return new AgentExecutionMetrics(
        windowFrom,
        windowTo,
        total,
        countActive(statuses),
        count(statuses, AgentExecutionStatus.SUCCEEDED),
        count(statuses, AgentExecutionStatus.FAILED),
        count(statuses, AgentExecutionStatus.CANCELLED),
        count(statuses, AgentExecutionStatus.REJECTED),
        statuses.stream().mapToLong(AgentExecutionStatusMetric::inputTokens)
            .sum(),
        statuses.stream().mapToLong(AgentExecutionStatusMetric::outputTokens)
            .sum(),
        statuses.stream().map(AgentExecutionStatusMetric::cost)
            .reduce(BigDecimal.ZERO, BigDecimal::add),
        statuses.stream().mapToLong(AgentExecutionStatusMetric::steps).sum(),
        statuses.stream()
            .mapToLong(AgentExecutionStatusMetric::humanInterventions).sum(),
        statuses,
        traces
    );
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiAgentExecution approve(
      final String agentId,
      final String executionId,
      final AgentExecutionDecisionRequest request
  ) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiAgentExecution execution = requireExecutionForUpdate(
        requireAgent(agentId, scope),
        scope,
        executionId
    );
    if (execution.getApprovedAt() != null) {
      return decorate(execution);
    }
    if (execution.getStatus() != AgentExecutionStatus.WAITING_APPROVAL) {
      throw new IllegalStateException(
          "Agent execution is not waiting for approval"
      );
    }
    final String actor = requireCurrentUserId();
    if (!Boolean.TRUE.equals(execution.getSelfApprovalAllowed())
        && actor.equals(execution.getRequestedBy())) {
      throw new IllegalStateException(
          "Agent execution requester cannot approve this execution"
      );
    }
    execution.setApprovedAt(Instant.now());
    execution.setApprovedBy(actor);
    execution.setApprovalComment(comment(request));
    execution.setStatus(AgentExecutionStatus.PENDING);
    execution = executionRepository.save(execution);
    publish(
        execution,
        AgentExecutionEventType.APPROVAL_GRANTED,
        null,
        null,
        actor,
        Map.of(),
        execution.getApprovedAt()
    );
    return decorate(execution);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiAgentExecution reject(
      final String agentId,
      final String executionId,
      final AgentExecutionDecisionRequest request
  ) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiAgentExecution execution = requireExecutionForUpdate(
        requireAgent(agentId, scope),
        scope,
        executionId
    );
    if (execution.getStatus() == AgentExecutionStatus.REJECTED) {
      return decorate(execution);
    }
    if (execution.getStatus() != AgentExecutionStatus.WAITING_APPROVAL) {
      throw new IllegalStateException(
          "Agent execution is not waiting for approval"
      );
    }
    String actor = requireCurrentUserId();
    if (!Boolean.TRUE.equals(execution.getSelfApprovalAllowed())
        && actor.equals(execution.getRequestedBy())) {
      throw new IllegalStateException(
          "Agent execution requester cannot reject this execution"
      );
    }
    Instant now = Instant.now();
    execution.setRejectedAt(now);
    execution.setRejectedBy(actor);
    execution.setRejectionReason(comment(request));
    execution.setStatus(AgentExecutionStatus.REJECTED);
    execution.setCompletedAt(now);
    execution.setPauseRequested(false);
    clearLease(execution);
    execution = executionRepository.save(execution);
    publish(
        execution,
        AgentExecutionEventType.APPROVAL_REJECTED,
        null,
        null,
        actor,
        Map.of(),
        now
    );
    return decorate(execution);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiAgentExecution pause(
      final String agentId,
      final String executionId,
      final AgentExecutionPauseRequest request
  ) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiAgentExecution execution = requireExecutionForUpdate(
        requireAgent(agentId, scope),
        scope,
        executionId
    );
    if (execution.getStatus() == AgentExecutionStatus.PAUSED
        || Boolean.TRUE.equals(execution.getPauseRequested())) {
      return decorate(execution);
    }
    if (isTerminal(execution.getStatus())) {
      throw new IllegalStateException(
          "Terminal Agent execution cannot be paused"
      );
    }
    Instant now = Instant.now();
    execution.setPauseRequested(true);
    execution.setPauseRequestedAt(now);
    execution.setPauseRequestedBy(requireCurrentUserId());
    execution.setPauseReason(text(
        request == null ? null : request.reason(),
        1024
    ));
    if (execution.getStatus() != AgentExecutionStatus.RUNNING) {
      execution.setStatus(AgentExecutionStatus.PAUSED);
      execution.setPausedAt(now);
      clearLease(execution);
    }
    execution = executionRepository.save(execution);
    publish(
        execution,
        execution.getStatus() == AgentExecutionStatus.PAUSED
            ? AgentExecutionEventType.PAUSED
            : AgentExecutionEventType.PAUSE_REQUESTED,
        execution.getCurrentTraceId(),
        execution.getCurrentHumanInterventionId(),
        execution.getPauseRequestedBy(),
        Map.of(),
        now
    );
    return decorate(execution);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiAgentExecution resume(
      final String agentId,
      final String executionId
  ) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiAgentExecution execution = requireExecutionForUpdate(
        requireAgent(agentId, scope),
        scope,
        executionId
    );
    if (execution.getStatus() != AgentExecutionStatus.PAUSED) {
      throw new IllegalStateException(
          "Only a paused Agent execution can be resumed"
      );
    }
    Instant now = Instant.now();
    execution.setPauseRequested(false);
    execution.setResumedAt(now);
    execution.setResumedBy(requireCurrentUserId());
    if (execution.getCurrentHumanInterventionId() != null) {
      AiAgentHumanIntervention intervention =
          requireInterventionForUpdate(execution);
      if (expireIntervention(execution, intervention, now)) {
        return decorate(executionRepository.save(execution));
      }
      intervention.setStatus(AgentHumanInterventionStatus.WAITING);
      if (intervention.getWaitingAt() == null) {
        intervention.setWaitingAt(now);
      }
      interventionRepository.save(intervention);
      execution.setStatus(AgentExecutionStatus.WAITING_HUMAN);
      execution.setNextPollAt(intervention.getExpiresAt());
    } else if (Boolean.TRUE.equals(execution.getApprovalRequired())
        && execution.getApprovedAt() == null) {
      execution.setStatus(AgentExecutionStatus.WAITING_APPROVAL);
    } else if (execution.getCurrentSkillExecutionId() != null) {
      execution.setStatus(AgentExecutionStatus.WAITING_SKILL);
      execution.setNextPollAt(now);
    } else {
      execution.setStatus(AgentExecutionStatus.PENDING);
    }
    execution = executionRepository.save(execution);
    publish(
        execution,
        AgentExecutionEventType.RESUMED,
        execution.getCurrentTraceId(),
        execution.getCurrentHumanInterventionId(),
        execution.getResumedBy(),
        Map.of(),
        now
    );
    if (execution.getStatus() == AgentExecutionStatus.WAITING_HUMAN) {
      publish(
          execution,
          AgentExecutionEventType.HUMAN_INTERVENTION_WAITING,
          null,
          execution.getCurrentHumanInterventionId(),
          execution.getResumedBy(),
          Map.of(),
          now
      );
    }
    return decorate(execution);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiAgentExecution requestHumanIntervention(
      final String agentId,
      final String executionId,
      final AgentHumanInterventionRequest request
  ) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiAgentExecution execution = requireExecutionForUpdate(
        requireAgent(agentId, scope),
        scope,
        executionId
    );
    if (!Boolean.TRUE.equals(execution.getHumanInterventionEnabled())) {
      throw new IllegalStateException(
          "Agent version does not allow human intervention"
      );
    }
    if (isTerminal(execution.getStatus())
        || execution.getStatus() == AgentExecutionStatus.WAITING_APPROVAL
        || (
          Boolean.TRUE.equals(execution.getApprovalRequired())
            && execution.getApprovedAt() == null
        )) {
      throw new IllegalStateException(
          "Agent execution cannot request human intervention now"
      );
    }
    if (execution.getCurrentHumanInterventionId() != null) {
      throw new IllegalStateException(
          "Agent execution already has a pending human intervention"
      );
    }
    int count = positive(execution.getHumanInterventionCount());
    if (count >= positive(execution.getMaximumHumanInterventions())) {
      throw new IllegalStateException(
          "Agent human intervention budget was exhausted"
      );
    }
    String prompt = required(
        request == null ? null : request.prompt(),
        "Human intervention prompt",
        1024
    );
    Instant now = Instant.now();
    AiAgentHumanIntervention intervention =
        new AiAgentHumanIntervention();
    intervention.setAgentId(execution.getAgentId());
    intervention.setExecutionId(execution.getId());
    intervention.setScopeType(execution.getScopeType());
    intervention.setTenantId(execution.getTenantId());
    intervention.setStatus(AgentHumanInterventionStatus.REQUESTED);
    intervention.setPrompt(prompt);
    intervention.setRequestedBy(requireCurrentUserId());
    intervention.setRequestedAt(now);
    intervention.setExpiresAt(now.plusSeconds(
        positive(execution.getHumanInterventionTimeoutSeconds())
    ));
    intervention = interventionRepository.save(intervention);
    execution.setCurrentHumanInterventionId(intervention.getId());
    execution.setHumanInterventionCount(count + 1);
    if (execution.getStatus() == AgentExecutionStatus.PENDING) {
      intervention.setStatus(AgentHumanInterventionStatus.WAITING);
      intervention.setWaitingAt(now);
      interventionRepository.save(intervention);
      execution.setStatus(AgentExecutionStatus.WAITING_HUMAN);
      execution.setNextPollAt(intervention.getExpiresAt());
      clearLeaseOnly(execution);
    }
    execution = executionRepository.save(execution);
    publish(
        execution,
        AgentExecutionEventType.HUMAN_INTERVENTION_REQUESTED,
        execution.getCurrentTraceId(),
        intervention.getId(),
        intervention.getRequestedBy(),
        Map.of("expiresAt", intervention.getExpiresAt().toString()),
        now
    );
    if (execution.getStatus() == AgentExecutionStatus.WAITING_HUMAN) {
      publish(
          execution,
          AgentExecutionEventType.HUMAN_INTERVENTION_WAITING,
          null,
          intervention.getId(),
          intervention.getRequestedBy(),
          Map.of("expiresAt", intervention.getExpiresAt().toString()),
          now
      );
    }
    return decorate(execution);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiAgentExecution respondHumanIntervention(
      final String agentId,
      final String executionId,
      final String interventionId,
      final AgentHumanInterventionResponseRequest request
  ) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiAgentExecution execution = requireExecutionForUpdate(
        requireAgent(agentId, scope),
        scope,
        executionId
    );
    if (execution.getStatus() != AgentExecutionStatus.WAITING_HUMAN
        || !required(
            interventionId,
            "Human intervention ID",
            64
        ).equals(execution.getCurrentHumanInterventionId())) {
      throw new IllegalStateException(
          "Agent execution is not waiting for this human intervention"
      );
    }
    AiAgentHumanIntervention intervention =
        requireInterventionForUpdate(execution);
    Instant now = Instant.now();
    if (expireIntervention(execution, intervention, now)) {
      return decorate(executionRepository.save(execution));
    }
    if (request == null || request.outcome() == null) {
      throw new IllegalArgumentException(
          "Human intervention outcome is required"
      );
    }
    final String actor = requireCurrentUserId();
    Map<String, Object> input = request.input() == null
        ? Map.of() : new LinkedHashMap<>(request.input());
    String responseJson = writeJson(input);
    assertPayloadSize(responseJson, "Human intervention response");
    intervention.setOutcome(request.outcome());
    intervention.setResponseJson(responseJson);
    intervention.setResponseComment(text(request.comment(), 1024));
    intervention.setRespondedBy(actor);
    intervention.setRespondedAt(now);
    if (request.outcome() == AgentHumanInterventionOutcome.CANCEL) {
      intervention.setStatus(AgentHumanInterventionStatus.CANCELLED);
      intervention.setCompletionReason(
          "Operator cancelled the Agent execution"
      );
      interventionRepository.save(intervention);
      execution.setStatus(AgentExecutionStatus.CANCELLED);
      execution.setCompletedAt(now);
      execution.setPauseRequested(false);
      execution.setCurrentHumanInterventionId(null);
      clearLease(execution);
      execution = executionRepository.save(execution);
      publish(
          execution,
          AgentExecutionEventType.HUMAN_INTERVENTION_CANCELLED,
          null,
          intervention.getId(),
          actor,
          Map.of(),
          now
      );
      publish(
          execution,
          AgentExecutionEventType.EXECUTION_CANCELLED,
          null,
          intervention.getId(),
          actor,
          Map.of("reason", "human_intervention"),
          now
      );
      return decorate(execution);
    }
    intervention.setStatus(AgentHumanInterventionStatus.COMPLETED);
    interventionRepository.save(intervention);
    appendHumanResponse(execution, intervention, input);
    execution.setCurrentHumanInterventionId(null);
    execution.setStatus(AgentExecutionStatus.PENDING);
    execution.setNextPollAt(null);
    clearLeaseOnly(execution);
    execution = executionRepository.save(execution);
    publish(
        execution,
        AgentExecutionEventType.HUMAN_INTERVENTION_COMPLETED,
        null,
        intervention.getId(),
        actor,
        Map.of(),
        now
    );
    return decorate(execution);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AiAgentExecution cancel(
      final String agentId,
      final String executionId
  ) {
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    AiAgentExecution execution = requireExecutionForUpdate(
        requireAgent(agentId, scope),
        scope,
        executionId
    );
    if (isTerminal(execution.getStatus())) {
      return decorate(execution);
    }
    final String interventionId = execution.getCurrentHumanInterventionId();
    final String actor = requireCurrentUserId();
    Instant now = Instant.now();
    execution.setStatus(AgentExecutionStatus.CANCELLED);
    execution.setCompletedAt(now);
    execution.setPauseRequested(false);
    cancelCurrentIntervention(execution, "Agent execution was cancelled");
    execution.setCurrentHumanInterventionId(null);
    clearLease(execution);
    execution = executionRepository.save(execution);
    if (interventionId != null) {
      publish(
          execution,
          AgentExecutionEventType.HUMAN_INTERVENTION_CANCELLED,
          null,
          interventionId,
          actor,
          Map.of(),
          now
      );
    }
    publish(
        execution,
        AgentExecutionEventType.EXECUTION_CANCELLED,
        execution.getCurrentTraceId(),
        interventionId,
        actor,
        Map.of("reason", "operator"),
        now
    );
    return decorate(execution);
  }

  private AiAgentDefinition requireAgent(
      final String agentId,
      final ScopeAssignment scope
  ) {
    AiAgentDefinition agent = agentRepository.findActiveById(required(
        agentId,
        "Agent ID",
        64
    )).orElseThrow(() -> new IllegalArgumentException(
        "Agent does not exist"
    ));
    if (agent.getScopeType() != scope.scopeType()
        || !java.util.Objects.equals(
            agent.getTenantId(),
            scope.tenantId()
        )) {
      throw new IllegalArgumentException("Agent does not exist");
    }
    return agent;
  }

  private AiAgentExecution requireExecutionForUpdate(
      final AiAgentDefinition agent,
      final ScopeAssignment scope,
      final String executionId
  ) {
    AiAgentExecution execution =
        executionRepository.findActiveByIdForUpdate(required(
            executionId,
            "Agent execution ID",
            64
        )).orElseThrow(() -> new IllegalArgumentException(
            "Agent execution does not exist"
        ));
    if (!agent.getId().equals(execution.getAgentId())
        || execution.getScopeType() != scope.scopeType()
        || !java.util.Objects.equals(
            execution.getTenantId(),
            scope.tenantId()
        )) {
      throw new IllegalArgumentException(
          "Agent execution does not exist"
      );
    }
    return execution;
  }

  private AiAgentExecution requireExecution(
      final AiAgentDefinition agent,
      final ScopeAssignment scope,
      final String executionId
  ) {
    AiAgentExecution execution = executionRepository.findActiveById(required(
        executionId,
        "Agent execution ID",
        64
    )).orElseThrow(() -> new IllegalArgumentException(
        "Agent execution does not exist"
    ));
    if (!agent.getId().equals(execution.getAgentId())
        || execution.getScopeType() != scope.scopeType()
        || !java.util.Objects.equals(
            execution.getTenantId(),
            scope.tenantId()
        )) {
      throw new IllegalArgumentException(
          "Agent execution does not exist"
      );
    }
    return execution;
  }

  private AiAgentExecution decorateSummary(
      final AiAgentExecution execution
  ) {
    execution.setTraces(List.of());
    execution.setHumanInterventions(List.of());
    return execution;
  }

  private AiAgentExecution decorate(final AiAgentExecution execution) {
    execution.setInput(readMap(
        execution.getInputJson(),
        "Agent execution input"
    ));
    execution.setOutput(execution.getOutputJson() == null
        ? null : readValue(
            execution.getOutputJson(),
            "Agent execution output"
        ));
    List<AiAgentExecutionTrace> traces =
        traceRepository.findAllActiveByExecutionId(execution.getId());
    traces.forEach(this::decorateTrace);
    execution.setTraces(List.copyOf(traces));
    List<AiAgentHumanIntervention> interventions =
        interventionRepository.findAllActiveByExecutionId(execution.getId());
    interventions.forEach(intervention -> intervention.setResponse(
        intervention.getResponseJson() == null
            ? null
            : readMap(
                intervention.getResponseJson(),
                "Agent human intervention response"
            )
    ));
    execution.setHumanInterventions(List.copyOf(interventions));
    return execution;
  }

  private AiAgentExecutionTrace decorateTrace(
      final AiAgentExecutionTrace trace
  ) {
    trace.setInput(trace.getInputJson() == null
        ? null : readValue(trace.getInputJson(), "Agent trace input"));
    trace.setOutput(trace.getOutputJson() == null
        ? null : readValue(trace.getOutputJson(), "Agent trace output"));
    return trace;
  }

  private AiAgentExecutionEvent decorateEvent(
      final AiAgentExecutionEvent event
  ) {
    event.setPayload(event.getPayloadJson() == null
        ? Map.of()
        : readMap(
            event.getPayloadJson(),
            "Agent execution event payload"
        ));
    return event;
  }

  private static AgentExecutionStatusMetric normalize(
      final AgentExecutionStatusMetric metric
  ) {
    return new AgentExecutionStatusMetric(
        metric.status(),
        metric.executionCount(),
        zero(metric.inputTokens()),
        zero(metric.outputTokens()),
        nonNegative(metric.cost()),
        zero(metric.steps()),
        zero(metric.humanInterventions())
    );
  }

  private static AgentTraceMetric normalize(final AgentTraceMetric metric) {
    return new AgentTraceMetric(
        metric.type(),
        metric.status(),
        metric.traceCount(),
        zero(metric.inputTokens()),
        zero(metric.outputTokens()),
        nonNegative(metric.cost())
    );
  }

  private static long count(
      final List<AgentExecutionStatusMetric> statuses,
      final AgentExecutionStatus status
  ) {
    return statuses.stream()
        .filter(metric -> metric.status() == status)
        .mapToLong(AgentExecutionStatusMetric::executionCount)
        .sum();
  }

  private static long countActive(
      final List<AgentExecutionStatusMetric> statuses
  ) {
    return statuses.stream()
        .filter(metric -> !isTerminal(metric.status()))
        .mapToLong(AgentExecutionStatusMetric::executionCount)
        .sum();
  }

  private static long zero(final Long value) {
    return value == null ? 0L : Math.max(0L, value);
  }

  private static BigDecimal nonNegative(final BigDecimal value) {
    return value == null || value.signum() < 0
        ? BigDecimal.ZERO : value;
  }

  private AiAgentHumanIntervention requireInterventionForUpdate(
      final AiAgentExecution execution
  ) {
    String interventionId = execution.getCurrentHumanInterventionId();
    AiAgentHumanIntervention intervention = interventionRepository
        .findActiveByIdForUpdate(interventionId)
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

  private boolean expireIntervention(
      final AiAgentExecution execution,
      final AiAgentHumanIntervention intervention,
      final Instant now
  ) {
    if (now.isBefore(intervention.getExpiresAt())) {
      return false;
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
      execution.setStatus(AgentExecutionStatus.CANCELLED);
    } else {
      execution.setStatus(AgentExecutionStatus.FAILED);
      execution.setErrorCode("AGENT_HUMAN_INTERVENTION_TIMEOUT");
      execution.setErrorMessage("Agent human intervention timed out");
    }
    clearLease(execution);
    publish(
        execution,
        AgentExecutionEventType.HUMAN_INTERVENTION_EXPIRED,
        null,
        intervention.getId(),
        null,
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
        null,
        Map.of("errorCode", "AGENT_HUMAN_INTERVENTION_TIMEOUT"),
        now
    );
    return true;
  }

  private void appendHumanResponse(
      final AiAgentExecution execution,
      final AiAgentHumanIntervention intervention,
      final Map<String, Object> input
  ) {
    final List<Message> conversation = new ArrayList<>(
        readMessages(execution.getConversationJson())
    );
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("type", "human_intervention_response");
    envelope.put("interventionId", intervention.getId());
    envelope.put("prompt", intervention.getPrompt());
    envelope.put("input", input);
    if (intervention.getResponseComment() != null) {
      envelope.put("comment", intervention.getResponseComment());
    }
    conversation.add(userMessage(writeJson(envelope)));
    execution.setConversationJson(writeJson(conversation));
  }

  private List<Message> readMessages(final String json) {
    try {
      return objectMapper.readValue(json, MESSAGE_LIST);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(
          "Agent execution conversation is corrupted",
          ex
      );
    }
  }

  private void cancelCurrentIntervention(
      final AiAgentExecution execution,
      final String reason
  ) {
    if (execution.getCurrentHumanInterventionId() == null) {
      return;
    }
    interventionRepository.findActiveByIdForUpdate(
        execution.getCurrentHumanInterventionId()
    ).ifPresent(intervention -> {
      if (intervention.getStatus() == AgentHumanInterventionStatus.REQUESTED
          || intervention.getStatus()
              == AgentHumanInterventionStatus.WAITING) {
        intervention.setStatus(AgentHumanInterventionStatus.CANCELLED);
        intervention.setRespondedAt(Instant.now());
        intervention.setCompletionReason(reason);
        interventionRepository.save(intervention);
      }
    });
  }

  private Message userMessage(final String inputJson) {
    return new Message(
        MessageRole.USER,
        List.of(new ContentBlock(
            ContentType.TEXT,
            inputJson,
            null,
            null,
            null,
            null,
            null
        ))
    );
  }

  private void publish(
      final AiAgentExecution execution,
      final AgentExecutionEventType type,
      final String traceId,
      final String interventionId,
      final String actorId,
      final Map<String, Object> payload,
      final Instant occurredAt
  ) {
    eventPublisher.publish(
        execution,
        type,
        traceId,
        interventionId,
        actorId,
        payload,
        occurredAt
    );
  }

  private Map<String, Object> readMap(
      final String json,
      final String label
  ) {
    try {
      return objectMapper.readValue(json, MAP_TYPE);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(label + " is corrupted", ex);
    }
  }

  private Object readValue(final String json, final String label) {
    try {
      return objectMapper.readValue(json, Object.class);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(label + " is corrupted", ex);
    }
  }

  private String writeJson(final Object value) {
    try {
      return canonicalMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException(
          "Agent execution value is not valid JSON",
          ex
      );
    }
  }

  private void assertPayloadSize(final String json, final String label) {
    int maximum = properties.getMaximumPayloadBytes() == null
        ? 256 * 1024 : properties.getMaximumPayloadBytes();
    if (maximum < 1024
        || json.getBytes(StandardCharsets.UTF_8).length > maximum) {
      throw new IllegalArgumentException(label + " is too large");
    }
  }

  private static Map<String, Object> map(
      final Object value,
      final String label
  ) {
    if (!(value instanceof Map<?, ?> source)) {
      throw new IllegalStateException(label + " is corrupted");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    source.forEach((key, item) -> result.put(String.valueOf(key), item));
    return result;
  }

  private static Map<String, Object> optionalMap(final Object value) {
    return value == null ? Map.of() : map(value, "Agent policy");
  }

  private static int integer(final Object value, final int fallback) {
    return value instanceof Number number ? number.intValue() : fallback;
  }

  private static BigDecimal decimal(
      final Object value,
      final String fallback
  ) {
    try {
      return value == null
          ? new BigDecimal(fallback)
          : new BigDecimal(String.valueOf(value));
    } catch (NumberFormatException ex) {
      throw new IllegalStateException("Agent cost budget is corrupted", ex);
    }
  }

  private static double decimalValue(
      final Object value,
      final double fallback
  ) {
    if (value == null) {
      return fallback;
    }
    try {
      return Double.parseDouble(String.valueOf(value));
    } catch (NumberFormatException ex) {
      throw new IllegalStateException(
          "Agent memory threshold is corrupted",
          ex
      );
    }
  }

  private static AgentMemoryScope memoryScope(final Object value) {
    if (value == null) {
      return AgentMemoryScope.SUBJECT;
    }
    try {
      return AgentMemoryScope.valueOf(String.valueOf(value));
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException(
          "Agent long-term memory scope is corrupted",
          ex
      );
    }
  }

  private static AgentHumanInterventionTimeoutAction interventionTimeoutAction(
      final Object value
  ) {
    if (value == null) {
      return AgentHumanInterventionTimeoutAction.FAIL;
    }
    try {
      return AgentHumanInterventionTimeoutAction.valueOf(
          String.valueOf(value)
      );
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException(
          "Agent human intervention timeout action is corrupted",
          ex
      );
    }
  }

  private static boolean bool(
      final Object value,
      final boolean fallback
  ) {
    return value instanceof Boolean flag ? flag : fallback;
  }

  private static String text(final Object value, final int maximum) {
    if (value == null || String.valueOf(value).isBlank()) {
      return null;
    }
    String normalized = String.valueOf(value).trim();
    return normalized.length() <= maximum
        ? normalized : normalized.substring(0, maximum);
  }

  private static String comment(
      final AgentExecutionDecisionRequest request
  ) {
    return text(request == null ? null : request.comment(), 1024);
  }

  private static String requireCurrentUserId() {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    String userId = context == null ? null : context.getUserId();
    if (userId == null || userId.isBlank()) {
      throw new IllegalStateException(
          "Authenticated user is required for Agent execution control"
      );
    }
    return userId;
  }

  private static boolean isTerminal(final AgentExecutionStatus status) {
    return status == AgentExecutionStatus.SUCCEEDED
        || status == AgentExecutionStatus.FAILED
        || status == AgentExecutionStatus.REJECTED
        || status == AgentExecutionStatus.CANCELLED;
  }

  private static int positive(final Integer value) {
    return value == null ? 0 : Math.max(0, value);
  }

  private static void clearLeaseOnly(final AiAgentExecution execution) {
    execution.setLeaseOwner(null);
    execution.setLeaseExpiresAt(null);
  }

  private static void clearLease(final AiAgentExecution execution) {
    execution.setLeaseOwner(null);
    execution.setLeaseExpiresAt(null);
    execution.setNextPollAt(null);
  }

  private static String required(
      final Object value,
      final String label,
      final int maximumLength
  ) {
    String normalized = value == null ? null : String.valueOf(value).trim();
    if (normalized == null || normalized.isEmpty()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    if (normalized.length() > maximumLength) {
      throw new IllegalArgumentException(label + " is too long");
    }
    return normalized;
  }

  private static String sha256(final String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }
}
