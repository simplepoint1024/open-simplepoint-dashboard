package org.simplepoint.plugin.ai.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentDefinition;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecution;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecutionEvent;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentHumanIntervention;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionEventFeed;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionMetrics;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionPauseRequest;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionStatusMetric;
import org.simplepoint.plugin.ai.agent.api.model.AgentHumanInterventionOutcome;
import org.simplepoint.plugin.ai.agent.api.model.AgentHumanInterventionRequest;
import org.simplepoint.plugin.ai.agent.api.model.AgentHumanInterventionResponseRequest;
import org.simplepoint.plugin.ai.agent.api.model.AgentHumanInterventionStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentHumanInterventionTimeoutAction;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceMetric;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceType;
import org.simplepoint.plugin.ai.agent.api.properties.AgentExecutionProperties;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentDefinitionRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentExecutionEventRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentExecutionRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentExecutionTraceRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentHumanInterventionRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentVersionRepository;
import org.simplepoint.plugin.ai.agent.service.execution.AiAgentExecutionEventPublisher;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.simplepoint.plugin.ai.skill.service.support.SkillJsonSchemaValidator;

class AiAgentExecutionServiceImplTest {

  private AiAgentExecutionRepository executionRepository;

  private AiAgentExecutionTraceRepository traceRepository;

  private AiAgentExecutionEventRepository eventRepository;

  private AiAgentHumanInterventionRepository interventionRepository;

  private AiAgentExecutionServiceImpl service;

  @BeforeEach
  void setUp() {
    final AiAgentDefinitionRepository agentRepository =
        mock(AiAgentDefinitionRepository.class);
    executionRepository = mock(AiAgentExecutionRepository.class);
    traceRepository = mock(AiAgentExecutionTraceRepository.class);
    eventRepository = mock(AiAgentExecutionEventRepository.class);
    interventionRepository =
        mock(AiAgentHumanInterventionRepository.class);
    final AiScopeAccessPolicy scopeAccessPolicy =
        mock(AiScopeAccessPolicy.class);
    AiAgentDefinition agent = new AiAgentDefinition();
    agent.setId("agent-1");
    agent.setScopeType(AiResourceScope.SYSTEM);
    when(agentRepository.findActiveById("agent-1"))
        .thenReturn(Optional.of(agent));
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
        mock(AiAgentVersionRepository.class),
        executionRepository,
        traceRepository,
        eventRepository,
        interventionRepository,
        mock(AiAgentExecutionEventPublisher.class),
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
    execution.setScopeType(AiResourceScope.SYSTEM);
    execution.setStatus(status);
    execution.setInputJson("{}");
    execution.setConversationJson("[]");
    execution.setApprovalRequired(false);
    execution.setPauseRequested(false);
    return execution;
  }
}
