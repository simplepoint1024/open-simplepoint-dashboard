package org.simplepoint.plugin.ai.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentDefinition;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecution;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecutionEvent;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecutionTrace;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentHumanIntervention;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentVersion;
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
import org.simplepoint.plugin.ai.agent.api.model.AgentPinnedChildCancelCommand;
import org.simplepoint.plugin.ai.agent.api.model.AgentStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceMetric;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceType;
import org.simplepoint.plugin.ai.agent.api.model.AgentVersionStatus;
import org.simplepoint.plugin.ai.agent.api.properties.AgentExecutionProperties;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentDefinitionRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentExecutionEventRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentExecutionRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentExecutionTraceRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentHumanInterventionRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentVersionRepository;
import org.simplepoint.plugin.ai.agent.service.execution.AiAgentActiveTraceCancellation;
import org.simplepoint.plugin.ai.agent.service.execution.AiAgentExecutionEventPublisher;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillExecutionService;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;

class AiAgentExecutionServiceImplTest {

  private AiAgentExecutionRepository executionRepository;

  private AiAgentDefinitionRepository agentRepository;

  private AiAgentVersionRepository versionRepository;

  private AiAgentExecutionTraceRepository traceRepository;

  private AiAgentExecutionEventRepository eventRepository;

  private AiAgentHumanInterventionRepository interventionRepository;

  private AiAgentExecutionEventPublisher eventPublisher;

  private AiAgentActiveTraceCancellation activeTraceCancellation;

  private AiSkillExecutionService skillExecutionService;

  private AiScopeAccessPolicy scopeAccessPolicy;

  private AiAgentExecutionServiceImpl service;

  @BeforeEach
  void setUp() {
    agentRepository = mock(AiAgentDefinitionRepository.class);
    versionRepository = mock(AiAgentVersionRepository.class);
    executionRepository = mock(AiAgentExecutionRepository.class);
    traceRepository = mock(AiAgentExecutionTraceRepository.class);
    eventRepository = mock(AiAgentExecutionEventRepository.class);
    interventionRepository =
        mock(AiAgentHumanInterventionRepository.class);
    eventPublisher = mock(AiAgentExecutionEventPublisher.class);
    skillExecutionService = mock(AiSkillExecutionService.class);
    activeTraceCancellation = spy(new AiAgentActiveTraceCancellation(
        traceRepository,
        skillExecutionService
    ));
    scopeAccessPolicy = mock(AiScopeAccessPolicy.class);
    AiAgentDefinition agent = new AiAgentDefinition();
    agent.setId("agent-1");
    agent.setScopeType(AiResourceScope.SYSTEM);
    agent.setEnabled(true);
    agent.setStatus(AgentStatus.ACTIVE);
    agent.setActiveVersionId("version-1");
    when(agentRepository.findActiveById("agent-1"))
        .thenReturn(Optional.of(agent));
    when(agentRepository.findActiveByIdForUpdate("agent-1"))
        .thenReturn(Optional.of(agent));
    AiAgentVersion version = new AiAgentVersion();
    version.setId("version-1");
    version.setAgentId("agent-1");
    version.setStatus(AgentVersionStatus.PUBLISHED);
    version.setContentHash("a".repeat(64));
    version.setPrimaryModelId("model-1");
    version.setManifestJson("""
        {"spec":{"budgets":{},"memory":{},"inputSchema":{
          "type":"object","additionalProperties":true
        }}}
        """);
    when(versionRepository.findActiveById("version-1"))
        .thenReturn(Optional.of(version));
    when(scopeAccessPolicy.currentManagementScope())
        .thenReturn(new ScopeAssignment(AiResourceScope.SYSTEM, null));
    when(traceRepository.findAllActiveByExecutionId(any()))
        .thenReturn(List.of());
    when(executionRepository.save(any(AiAgentExecution.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(interventionRepository.save(any(AiAgentHumanIntervention.class)))
        .thenAnswer(invocation -> {
          AiAgentHumanIntervention intervention = invocation.getArgument(0);
          if (intervention.getId() == null) {
            intervention.setId("intervention-1");
          }
          return intervention;
        });
    when(interventionRepository.findAllActiveByExecutionId(any()))
        .thenReturn(List.of());
    service = new AiAgentExecutionServiceImpl(
        agentRepository,
        versionRepository,
        executionRepository,
        traceRepository,
        eventRepository,
        interventionRepository,
        eventPublisher,
        activeTraceCancellation,
        scopeAccessPolicy,
        new SkillJsonSchemaValidator(),
        new AgentExecutionProperties(),
        JsonMapper.builder().build()
    );
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId("operator-1");
    context.setAttributes(Map.of());
    RequestContextHolder.setContext(
        RequestContextHolder.AUTHORIZATION_CONTEXT_KEY,
        context
    );
  }

  @AfterEach
  void tearDown() {
    RequestContextHolder.clearContext(
        RequestContextHolder.AUTHORIZATION_CONTEXT_KEY
    );
  }

  @Test
  void pausesPendingExecutionImmediatelyAndResumesIt() {
    AiAgentExecution execution = execution(AgentExecutionStatus.PENDING);
    when(executionRepository.findActiveByIdForUpdate("execution-1"))
        .thenReturn(Optional.of(execution));

    AiAgentExecution paused = service.pause(
        "agent-1",
        "execution-1",
        new AgentExecutionPauseRequest("operator review")
    );

    assertThat(paused.getStatus()).isEqualTo(AgentExecutionStatus.PAUSED);
    assertThat(paused.getPauseRequested()).isTrue();
    assertThat(paused.getPauseRequestedBy()).isEqualTo("operator-1");
    assertThat(paused.getPauseReason()).isEqualTo("operator review");
    assertThat(paused.getPausedAt()).isNotNull();

    AiAgentExecution resumed = service.resume("agent-1", "execution-1");

    assertThat(resumed.getStatus()).isEqualTo(AgentExecutionStatus.PENDING);
    assertThat(resumed.getPauseRequested()).isFalse();
    assertThat(resumed.getResumedBy()).isEqualTo("operator-1");
    assertThat(resumed.getResumedAt()).isNotNull();
  }

  @Test
  void serializesStartsAndReturnsTheExistingIdempotentExecution() {
    AtomicReference<AiAgentExecution> persisted = new AtomicReference<>();
    when(executionRepository.findActiveByIdempotency(
        any(),
        any(),
        any(),
        any()
    )).thenAnswer(invocation -> Optional.ofNullable(persisted.get()));
    when(executionRepository.save(any(AiAgentExecution.class)))
        .thenAnswer(invocation -> {
          AiAgentExecution execution = invocation.getArgument(0);
          execution.setId("execution-idempotent");
          persisted.set(execution);
          return execution;
        });
    AgentExecutionStartRequest request = new AgentExecutionStartRequest(
        "stable-request-key",
        Map.of("request", "hello")
    );

    AiAgentExecution first = service.start("agent-1", request);
    AiAgentExecution replay = service.start("agent-1", request);

    assertThat(replay.getId()).isEqualTo(first.getId());
    verify(agentRepository, times(2)).findActiveByIdForUpdate("agent-1");
    verify(executionRepository).save(any(AiAgentExecution.class));
  }

  @Test
  void rejectsAnIdempotencyKeyReusedWithDifferentInput() {
    AtomicReference<AiAgentExecution> persisted = new AtomicReference<>();
    when(executionRepository.findActiveByIdempotency(
        any(),
        any(),
        any(),
        any()
    )).thenAnswer(invocation -> Optional.ofNullable(persisted.get()));
    when(executionRepository.save(any(AiAgentExecution.class)))
        .thenAnswer(invocation -> {
          AiAgentExecution execution = invocation.getArgument(0);
          execution.setId("execution-idempotent");
          persisted.set(execution);
          return execution;
        });
    service.start("agent-1", new AgentExecutionStartRequest(
        "stable-request-key",
        Map.of("request", "first")
    ));

    assertThatThrownBy(() -> service.start(
        "agent-1",
        new AgentExecutionStartRequest(
            "stable-request-key",
            Map.of("request", "different")
        )
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("different input");
    verify(executionRepository).save(any(AiAgentExecution.class));
  }

  @Test
  void runningExecutionOnlyRecordsCooperativePauseRequest() {
    AiAgentExecution execution = execution(AgentExecutionStatus.RUNNING);
    execution.setLeaseOwner("worker-1");
    when(executionRepository.findActiveByIdForUpdate("execution-1"))
        .thenReturn(Optional.of(execution));

    AiAgentExecution result = service.pause(
        "agent-1",
        "execution-1",
        new AgentExecutionPauseRequest(null)
    );

    assertThat(result.getStatus()).isEqualTo(AgentExecutionStatus.RUNNING);
    assertThat(result.getPauseRequested()).isTrue();
    assertThat(result.getPausedAt()).isNull();
    assertThat(result.getLeaseOwner()).isEqualTo("worker-1");
  }

  @Test
  void waitsForAndContinuesFromStructuredHumanInput() {
    AiAgentExecution execution = execution(AgentExecutionStatus.PENDING);
    execution.setHumanInterventionEnabled(true);
    execution.setMaximumHumanInterventions(2);
    execution.setHumanInterventionTimeoutSeconds(3600);
    execution.setHumanInterventionTimeoutAction(
        AgentHumanInterventionTimeoutAction.FAIL
    );
    execution.setHumanInterventionCount(0);
    when(executionRepository.findActiveByIdForUpdate("execution-1"))
        .thenReturn(Optional.of(execution));

    AiAgentExecution waiting = service.requestHumanIntervention(
        "agent-1",
        "execution-1",
        new AgentHumanInterventionRequest("Confirm deployment region")
    );

    assertThat(waiting.getStatus())
        .isEqualTo(AgentExecutionStatus.WAITING_HUMAN);
    assertThat(waiting.getCurrentHumanInterventionId())
        .isEqualTo("intervention-1");
    assertThat(waiting.getHumanInterventionCount()).isEqualTo(1);
    AiAgentHumanIntervention intervention = new AiAgentHumanIntervention();
    intervention.setId("intervention-1");
    intervention.setAgentId("agent-1");
    intervention.setExecutionId("execution-1");
    intervention.setScopeType(AiResourceScope.SYSTEM);
    intervention.setStatus(AgentHumanInterventionStatus.WAITING);
    intervention.setPrompt("Confirm deployment region");
    intervention.setRequestedBy("operator-1");
    intervention.setRequestedAt(java.time.Instant.now());
    intervention.setWaitingAt(java.time.Instant.now());
    intervention.setExpiresAt(java.time.Instant.now().plusSeconds(3600));
    when(interventionRepository.findActiveByIdForUpdate("intervention-1"))
        .thenReturn(Optional.of(intervention));

    AiAgentExecution continued = service.respondHumanIntervention(
        "agent-1",
        "execution-1",
        "intervention-1",
        new AgentHumanInterventionResponseRequest(
            AgentHumanInterventionOutcome.CONTINUE,
            Map.of("region", "cn-east"),
            "approved"
        )
    );

    assertThat(continued.getStatus())
        .isEqualTo(AgentExecutionStatus.PENDING);
    assertThat(continued.getCurrentHumanInterventionId()).isNull();
    assertThat(continued.getConversationJson())
        .contains("human_intervention_response")
        .contains("cn-east");
    assertThat(intervention.getStatus())
        .isEqualTo(AgentHumanInterventionStatus.COMPLETED);
    assertThat(intervention.getRespondedBy()).isEqualTo("operator-1");
  }

  @Test
  void clearsCurrentHumanInterventionWhenOperatorCancels() {
    AiAgentExecution execution =
        execution(AgentExecutionStatus.WAITING_HUMAN);
    execution.setCurrentHumanInterventionId("intervention-1");
    when(executionRepository.findActiveByIdForUpdate("execution-1"))
        .thenReturn(Optional.of(execution));
    AiAgentHumanIntervention intervention = new AiAgentHumanIntervention();
    intervention.setId("intervention-1");
    intervention.setAgentId("agent-1");
    intervention.setExecutionId("execution-1");
    intervention.setScopeType(AiResourceScope.SYSTEM);
    intervention.setStatus(AgentHumanInterventionStatus.WAITING);
    intervention.setPrompt("Confirm cancellation");
    intervention.setRequestedBy("operator-1");
    intervention.setRequestedAt(java.time.Instant.now());
    intervention.setWaitingAt(java.time.Instant.now());
    intervention.setExpiresAt(java.time.Instant.now().plusSeconds(3600));
    when(interventionRepository.findActiveByIdForUpdate("intervention-1"))
        .thenReturn(Optional.of(intervention));

    AiAgentExecution cancelled = service.respondHumanIntervention(
        "agent-1",
        "execution-1",
        "intervention-1",
        new AgentHumanInterventionResponseRequest(
            AgentHumanInterventionOutcome.CANCEL,
            Map.of(),
            "cancelled by operator"
        )
    );

    assertThat(cancelled.getStatus())
        .isEqualTo(AgentExecutionStatus.CANCELLED);
    assertThat(cancelled.getCurrentHumanInterventionId()).isNull();
    assertThat(cancelled.getCompletedAt()).isNotNull();
    assertThat(intervention.getStatus())
        .isEqualTo(AgentHumanInterventionStatus.CANCELLED);
    verify(activeTraceCancellation).cancelActiveTrace(
        execution,
        "operator-1",
        "Operator cancelled the Agent execution",
        cancelled.getCompletedAt()
    );
  }

  @Test
  void expiredHumanInterventionCancelClosesActiveTraceOnce() {
    AiAgentExecution execution =
        execution(AgentExecutionStatus.WAITING_HUMAN);
    execution.setCurrentHumanInterventionId("intervention-1");
    execution.setHumanInterventionTimeoutAction(
        AgentHumanInterventionTimeoutAction.CANCEL
    );
    when(executionRepository.findActiveByIdForUpdate("execution-1"))
        .thenReturn(Optional.of(execution));
    AiAgentHumanIntervention intervention = new AiAgentHumanIntervention();
    intervention.setId("intervention-1");
    intervention.setAgentId("agent-1");
    intervention.setExecutionId("execution-1");
    intervention.setScopeType(AiResourceScope.SYSTEM);
    intervention.setStatus(AgentHumanInterventionStatus.WAITING);
    intervention.setExpiresAt(Instant.now().minusSeconds(1));
    when(interventionRepository.findActiveByIdForUpdate("intervention-1"))
        .thenReturn(Optional.of(intervention));

    AiAgentExecution cancelled = service.respondHumanIntervention(
        "agent-1",
        "execution-1",
        "intervention-1",
        null
    );

    assertThat(cancelled.getStatus())
        .isEqualTo(AgentExecutionStatus.CANCELLED);
    assertThat(intervention.getStatus())
        .isEqualTo(AgentHumanInterventionStatus.EXPIRED);
    verify(activeTraceCancellation).cancelActiveTrace(
        execution,
        "agent-runtime",
        "Agent human intervention timed out",
        cancelled.getCompletedAt()
    );
    verify(executionRepository).save(execution);
    verify(eventPublisher).publish(
        execution,
        org.simplepoint.plugin.ai.agent.api.model.AgentExecutionEventType
            .EXECUTION_CANCELLED,
        null,
        "intervention-1",
        "agent-runtime",
        Map.of("errorCode", "AGENT_HUMAN_INTERVENTION_TIMEOUT"),
        cancelled.getCompletedAt()
    );
  }

  @Test
  void publicCancelUsesUnifiedTraceClosureAndIsTerminalIdempotent() {
    AiAgentExecution execution = execution(AgentExecutionStatus.RUNNING);
    when(executionRepository.findActiveByIdForUpdate("execution-1"))
        .thenReturn(Optional.of(execution));

    AiAgentExecution cancelled = service.cancel("agent-1", "execution-1");
    AiAgentExecution repeated = service.cancel("agent-1", "execution-1");

    assertThat(repeated).isSameAs(cancelled);
    assertThat(cancelled.getStatus())
        .isEqualTo(AgentExecutionStatus.CANCELLED);
    verify(activeTraceCancellation).cancelActiveTrace(
        execution,
        "operator-1",
        "Agent execution was cancelled",
        cancelled.getCompletedAt()
    );
    verify(executionRepository, times(1)).save(execution);
    verify(eventPublisher, times(1)).publish(
        execution,
        org.simplepoint.plugin.ai.agent.api.model.AgentExecutionEventType
            .EXECUTION_CANCELLED,
        null,
        null,
        "operator-1",
        Map.of("reason", "operator"),
        cancelled.getCompletedAt()
    );
  }

  @Test
  void internalPinnedCancelValidatesBoundaryWithoutManagementScope() {
    AiAgentExecution execution = execution(AgentExecutionStatus.WAITING_SKILL);
    execution.setTenantId("tenant-a");
    execution.setScopeType(AiResourceScope.TENANT);
    when(executionRepository.findActiveByIdForUpdate("execution-1"))
        .thenReturn(Optional.of(execution));

    AiAgentExecution cancelled = service.cancelPinnedChild(
        pinnedCancelCommand()
    );

    assertThat(cancelled.getStatus())
        .isEqualTo(AgentExecutionStatus.CANCELLED);
    verify(scopeAccessPolicy, never()).currentManagementScope();
    verify(activeTraceCancellation).cancelActiveTrace(
        execution,
        "workflow-runtime-a",
        "Parent Workflow was cancelled",
        cancelled.getCompletedAt()
    );
    verify(executionRepository).save(execution);
  }

  @Test
  void internalWaitingSkillCancelCascadesThroughTraceToPinnedSkill() {
    AiAgentExecution execution = execution(AgentExecutionStatus.WAITING_SKILL);
    execution.setTenantId("tenant-a");
    execution.setScopeType(AiResourceScope.TENANT);
    execution.setCurrentTraceId("trace-1");
    execution.setCurrentModelId("last-model-1");
    execution.setCurrentSkillBindingId("binding-1");
    execution.setCurrentSkillExecutionId("skill-execution-1");
    execution.setCurrentToolCallId("tool-call-1");
    AiAgentExecutionTrace trace = new AiAgentExecutionTrace();
    trace.setId("trace-1");
    trace.setExecutionId("execution-1");
    trace.setType(AgentTraceType.SKILL);
    trace.setStatus(AgentTraceStatus.RUNNING);
    trace.setSkillBindingId("binding-1");
    trace.setSkillId("skill-1");
    trace.setSkillVersionId("skill-version-1");
    trace.setSkillExecutionId("skill-execution-1");
    trace.setToolCallId("tool-call-1");
    when(executionRepository.findActiveByIdForUpdate("execution-1"))
        .thenReturn(Optional.of(execution));
    when(traceRepository.findActiveById("trace-1"))
        .thenReturn(Optional.of(trace));

    AiAgentExecution cancelled = service.cancelPinnedChild(
        pinnedCancelCommand()
    );

    assertThat(cancelled.getStatus())
        .isEqualTo(AgentExecutionStatus.CANCELLED);
    assertThat(trace.getStatus()).isEqualTo(AgentTraceStatus.CANCELLED);
    assertThat(trace.getCompletedAt()).isEqualTo(cancelled.getCompletedAt());
    assertThat(execution.getCurrentTraceId()).isNull();
    assertThat(execution.getCurrentModelId()).isNull();
    assertThat(execution.getCurrentSkillBindingId()).isNull();
    assertThat(execution.getCurrentSkillExecutionId()).isNull();
    assertThat(execution.getCurrentToolCallId()).isNull();
    verify(skillExecutionService).cancelPinnedChild(
        org.mockito.ArgumentMatchers.argThat(command ->
            "skill-1".equals(command.skillId())
                && "skill-version-1".equals(command.skillVersionId())
                && "skill-execution-1".equals(command.executionId())
                && "execution-1:tool-call-1".equals(
                    command.idempotencyKey()
                ))
    );
    verify(traceRepository).save(trace);
    verify(executionRepository).save(execution);
  }

  @Test
  void internalPinnedCancelRejectsResourceVersionScopeTenantAndKeyMismatch() {
    AiAgentExecution execution = execution(AgentExecutionStatus.WAITING_SKILL);
    execution.setTenantId("tenant-a");
    execution.setScopeType(AiResourceScope.TENANT);
    when(executionRepository.findActiveByIdForUpdate("execution-1"))
        .thenReturn(Optional.of(execution));

    List<AgentPinnedChildCancelCommand> mismatches = List.of(
        pinnedCancelCommand(
            "other-agent", "version-1", AiResourceScope.TENANT,
            "tenant-a", "workflow-execution-a:node-a"
        ),
        pinnedCancelCommand(
            "agent-1", "other-version", AiResourceScope.TENANT,
            "tenant-a", "workflow-execution-a:node-a"
        ),
        pinnedCancelCommand(
            "agent-1", "version-1", AiResourceScope.SYSTEM,
            null, "workflow-execution-a:node-a"
        ),
        pinnedCancelCommand(
            "agent-1", "version-1", AiResourceScope.TENANT,
            "tenant-b", "workflow-execution-a:node-a"
        ),
        pinnedCancelCommand(
            "agent-1", "version-1", AiResourceScope.TENANT,
            "tenant-a", "different-parent:node-a"
        )
    );

    mismatches.forEach(command -> assertThatThrownBy(
        () -> service.cancelPinnedChild(command)
    ).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match cancellation command"));

    assertThat(execution.getStatus())
        .isEqualTo(AgentExecutionStatus.WAITING_SKILL);
    verify(scopeAccessPolicy, never()).currentManagementScope();
    verify(executionRepository, never()).save(any());
    verifyNoInteractions(activeTraceCancellation, eventPublisher);
  }

  @Test
  void internalPinnedCancelLeavesParentUntouchedWhenTraceRelationIsCorrupt() {
    AiAgentExecution execution = execution(AgentExecutionStatus.WAITING_SKILL);
    execution.setTenantId("tenant-a");
    execution.setScopeType(AiResourceScope.TENANT);
    when(executionRepository.findActiveByIdForUpdate("execution-1"))
        .thenReturn(Optional.of(execution));
    org.mockito.Mockito.doThrow(new IllegalStateException(
        "Agent active Skill trace relationship is corrupted"
    )).when(activeTraceCancellation).cancelActiveTrace(
        any(), any(), any(), any()
    );

    assertThatThrownBy(() -> service.cancelPinnedChild(
        pinnedCancelCommand()
    )).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("relationship is corrupted");

    assertThat(execution.getStatus())
        .isEqualTo(AgentExecutionStatus.WAITING_SKILL);
    assertThat(execution.getCompletedAt()).isNull();
    verify(executionRepository, never()).save(any());
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void internalPinnedCancelIsIdempotentForEveryTerminalStatus() {
    for (AgentExecutionStatus status : List.of(
        AgentExecutionStatus.SUCCEEDED,
        AgentExecutionStatus.FAILED,
        AgentExecutionStatus.REJECTED,
        AgentExecutionStatus.CANCELLED
    )) {
      AiAgentExecution execution = execution(status);
      execution.setTenantId("tenant-a");
      execution.setScopeType(AiResourceScope.TENANT);
      when(executionRepository.findActiveByIdForUpdate("execution-1"))
          .thenReturn(Optional.of(execution));

      assertThat(service.cancelPinnedChild(pinnedCancelCommand()).getStatus())
          .isEqualTo(status);
    }

    verify(scopeAccessPolicy, never()).currentManagementScope();
    verify(executionRepository, never()).save(any());
    verifyNoInteractions(activeTraceCancellation, eventPublisher);
  }

  @Test
  void readsDurableEventsWithAnExclusiveCursor() {
    AiAgentExecution execution = execution(AgentExecutionStatus.RUNNING);
    when(executionRepository.findActiveById("execution-1"))
        .thenReturn(Optional.of(execution));
    AiAgentExecutionEvent first = event(4, "{\"modelId\":\"model-1\"}");
    AiAgentExecutionEvent lookahead = event(5, null);
    when(eventRepository.findActiveAfterSequence(
        org.mockito.ArgumentMatchers.eq("execution-1"),
        org.mockito.ArgumentMatchers.eq(3L),
        any()
    )).thenReturn(List.of(first, lookahead));

    AgentExecutionEventFeed result = service.findEvents(
        "agent-1",
        "execution-1",
        3,
        1
    );

    assertThat(result.afterSequence()).isEqualTo(3);
    assertThat(result.nextSequence()).isEqualTo(4);
    assertThat(result.hasMore()).isTrue();
    assertThat(result.executionStatus())
        .isEqualTo(AgentExecutionStatus.RUNNING);
    assertThat(result.events()).singleElement()
        .satisfies(item -> assertThat(item.getPayload())
            .containsEntry("modelId", "model-1"));
  }

  @Test
  void filtersHistoricalFailureEventPayloads() {
    String sentinel =
        "provider body token=sk-live-event https://internal.example";
    AiAgentExecution execution = execution(AgentExecutionStatus.FAILED);
    when(executionRepository.findActiveById("execution-1"))
        .thenReturn(Optional.of(execution));
    AiAgentExecutionEvent event = event(
        4,
        "{\"errorCode\":\"UPSTREAM_FAILURE\","
            + "\"errorMessage\":\"" + sentinel + "\"}"
    );
    event.setType(AgentExecutionEventType.EXECUTION_FAILED);
    when(eventRepository.findActiveAfterSequence(
        org.mockito.ArgumentMatchers.eq("execution-1"),
        org.mockito.ArgumentMatchers.eq(3L),
        any()
    )).thenReturn(List.of(event));

    AgentExecutionEventFeed result = service.findEvents(
        "agent-1",
        "execution-1",
        3,
        10
    );

    assertThat(result.events()).singleElement().satisfies(item -> {
      assertThat(item.getId()).isEqualTo("event-4");
      assertThat(item.getPayload()).containsExactly(
          Map.entry("errorCode", "AGENT_EXECUTION_FAILED")
      );
      assertThat(String.valueOf(item.getPayload()))
          .doesNotContain(sentinel);
    });
  }

  @Test
  void filtersHistoricalExecutionAndTraceDiagnosticsFromResponses() {
    String sentinel =
        "https://provider.example/fail?token=sk-live-historical";
    AiAgentExecution execution = execution(AgentExecutionStatus.FAILED);
    execution.setErrorCode("UPSTREAM_" + sentinel);
    execution.setErrorMessage(sentinel);
    AiAgentExecutionTrace trace = new AiAgentExecutionTrace();
    trace.setId("trace-historical");
    trace.setExecutionId(execution.getId());
    trace.setType(AgentTraceType.SKILL);
    trace.setStatus(AgentTraceStatus.FAILED);
    trace.setErrorCode("PROVIDER_BODY");
    trace.setErrorMessage(sentinel);
    trace.setOutputJson("{\"message\":\"" + sentinel + "\"}");
    when(executionRepository.findActiveById("execution-1"))
        .thenReturn(Optional.of(execution));
    when(traceRepository.findAllActiveByExecutionId("execution-1"))
        .thenReturn(List.of(trace));

    AiAgentExecution result = service.find("agent-1", "execution-1")
        .orElseThrow();

    assertThat(result.getId()).isEqualTo("execution-1");
    assertThat(result.getErrorCode()).isEqualTo("AGENT_EXECUTION_FAILED");
    assertThat(result.getErrorMessage())
        .isEqualTo("AGENT_EXECUTION_FAILED");
    assertThat(result.getTraces()).singleElement().satisfies(item -> {
      assertThat(item.getId()).isEqualTo("trace-historical");
      assertThat(item.getErrorCode()).isEqualTo("AGENT_RUNTIME_FAILED");
      assertThat(item.getErrorMessage()).isEqualTo("AGENT_RUNTIME_FAILED");
      assertThat(item.getOutput()).isEqualTo(Map.of(
          "error", true,
          "errorCode", "AGENT_RUNTIME_FAILED"
      ));
      assertThat(String.valueOf(item.getOutput())).doesNotContain(sentinel);
    });
  }

  @Test
  void aggregatesRestartSafeMetricsFromPersistentRecords() {
    Instant from = Instant.parse("2026-07-29T00:00:00Z");
    Instant to = Instant.parse("2026-07-30T00:00:00Z");
    when(executionRepository.aggregateByAgentAndWindow(
        "agent-1",
        AiResourceScope.SYSTEM,
        null,
        from,
        to
    )).thenReturn(List.of(
        new AgentExecutionStatusMetric(
            AgentExecutionStatus.SUCCEEDED,
            2,
            10L,
            20L,
            new BigDecimal("1.25"),
            3L,
            1L
        ),
        new AgentExecutionStatusMetric(
            AgentExecutionStatus.RUNNING,
            1,
            4L,
            0L,
            BigDecimal.ZERO,
            1L,
            0L
        )
    ));
    when(traceRepository.aggregateByAgentAndWindow(
        "agent-1",
        from,
        to
    )).thenReturn(List.of(new AgentTraceMetric(
        AgentTraceType.MODEL,
        AgentTraceStatus.SUCCEEDED,
        2,
        10L,
        20L,
        new BigDecimal("1.25")
    )));

    AgentExecutionMetrics result = service.metrics(
        "agent-1",
        from,
        to
    );

    assertThat(result.totalExecutions()).isEqualTo(3);
    assertThat(result.activeExecutions()).isEqualTo(1);
    assertThat(result.succeededExecutions()).isEqualTo(2);
    assertThat(result.inputTokens()).isEqualTo(14);
    assertThat(result.outputTokens()).isEqualTo(20);
    assertThat(result.cost()).isEqualByComparingTo("1.25");
    assertThat(result.traces()).singleElement()
        .extracting(AgentTraceMetric::traceCount)
        .isEqualTo(2L);
  }

  private static AiAgentExecutionEvent event(
      final int sequence,
      final String payloadJson
  ) {
    AiAgentExecutionEvent event = new AiAgentExecutionEvent();
    event.setId("event-" + sequence);
    event.setSequence(sequence);
    event.setPayloadJson(payloadJson);
    return event;
  }

  private static AiAgentExecution execution(
      final AgentExecutionStatus status
  ) {
    AiAgentExecution execution = new AiAgentExecution();
    execution.setId("execution-1");
    execution.setAgentId("agent-1");
    execution.setAgentVersionId("version-1");
    execution.setIdempotencyKeyHash(sha256(
        "workflow-execution-a:node-a"
    ));
    execution.setScopeType(AiResourceScope.SYSTEM);
    execution.setStatus(status);
    execution.setInputJson("{}");
    execution.setConversationJson("[]");
    execution.setApprovalRequired(false);
    execution.setPauseRequested(false);
    return execution;
  }

  private static AgentPinnedChildCancelCommand pinnedCancelCommand() {
    return pinnedCancelCommand(
        "agent-1",
        "version-1",
        AiResourceScope.TENANT,
        "tenant-a",
        "workflow-execution-a:node-a"
    );
  }

  private static AgentPinnedChildCancelCommand pinnedCancelCommand(
      final String agentId,
      final String agentVersionId,
      final AiResourceScope scope,
      final String tenantId,
      final String idempotencyKey
  ) {
    return new AgentPinnedChildCancelCommand(
        agentId,
        agentVersionId,
        "execution-1",
        scope,
        tenantId,
        idempotencyKey,
        "workflow-runtime-a",
        "Parent Workflow was cancelled"
    );
  }

  private static String sha256(final String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
