package org.simplepoint.plugin.ai.agent.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecution;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecutionTrace;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentHumanIntervention;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentHumanInterventionStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceType;
import org.simplepoint.plugin.ai.agent.api.properties.AgentExecutionProperties;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentExecutionRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentExecutionTraceRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentHumanInterventionRepository;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentMemoryRepository;
import org.simplepoint.plugin.ai.agent.service.execution.AiAgentExecutionCoordinator.ExecutionTask;
import org.simplepoint.plugin.ai.agent.service.execution.AiAgentExecutionCoordinator.SkillFailureCheckpoint;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

class AiAgentExecutionCoordinatorTest {

  private AiAgentExecutionRepository executionRepository;

  private AiAgentExecutionTraceRepository traceRepository;

  private AiAgentHumanInterventionRepository interventionRepository;

  private AiAgentExecutionCoordinator coordinator;

  @BeforeEach
  void setUp() {
    executionRepository = mock(AiAgentExecutionRepository.class);
    traceRepository = mock(AiAgentExecutionTraceRepository.class);
    interventionRepository = mock(AiAgentHumanInterventionRepository.class);
    when(executionRepository.save(any(AiAgentExecution.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(traceRepository.save(any(AiAgentExecutionTrace.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    coordinator = new AiAgentExecutionCoordinator(
        executionRepository,
        traceRepository,
        interventionRepository,
        mock(AiAgentExecutionEventPublisher.class),
        mock(AiAgentMemoryRepository.class),
        new AgentExecutionProperties()
    );
  }

  @Test
  void pausesBeforeStartingAnotherModelInvocation() {
    AiAgentExecution execution = new AiAgentExecution();
    execution.setId("execution-1");
    execution.setStatus(AgentExecutionStatus.RUNNING);
    execution.setPauseRequested(true);
    execution.setLeaseOwner("worker-1");
    execution.setLeaseToken(7);
    execution.setLeaseExpiresAt(Instant.now().plusSeconds(60));
    execution.setMaximumSteps(10);
    when(executionRepository.findActiveByIdForUpdate("execution-1"))
        .thenReturn(Optional.of(execution));

    String traceId = coordinator.beginModel(
        new ExecutionTask("execution-1", "worker-1", 7),
        "model-1",
        "{}",
        "a".repeat(64)
    );

    assertThat(traceId).isNull();
    assertThat(execution.getStatus()).isEqualTo(AgentExecutionStatus.PAUSED);
    assertThat(execution.getPausedAt()).isNotNull();
    assertThat(execution.getLeaseOwner()).isNull();
    assertThat(execution.getLeaseExpiresAt()).isNull();
    verifyNoInteractions(traceRepository);
  }

  @Test
  void waitsForHumanBeforeStartingAnotherModelInvocation() {
    AiAgentExecution execution = new AiAgentExecution();
    execution.setId("execution-1");
    execution.setAgentId("agent-1");
    execution.setScopeType(AiResourceScope.SYSTEM);
    execution.setStatus(AgentExecutionStatus.RUNNING);
    execution.setPauseRequested(false);
    execution.setCurrentHumanInterventionId("intervention-1");
    execution.setLeaseOwner("worker-1");
    execution.setLeaseToken(7);
    execution.setLeaseExpiresAt(Instant.now().plusSeconds(60));
    execution.setMaximumSteps(10);
    AiAgentHumanIntervention intervention =
        new AiAgentHumanIntervention();
    intervention.setId("intervention-1");
    intervention.setAgentId("agent-1");
    intervention.setExecutionId("execution-1");
    intervention.setScopeType(AiResourceScope.SYSTEM);
    intervention.setStatus(AgentHumanInterventionStatus.REQUESTED);
    intervention.setExpiresAt(Instant.now().plusSeconds(3600));
    when(executionRepository.findActiveByIdForUpdate("execution-1"))
        .thenReturn(Optional.of(execution));
    when(interventionRepository.findActiveByIdForUpdate("intervention-1"))
        .thenReturn(Optional.of(intervention));

    String traceId = coordinator.beginModel(
        new ExecutionTask("execution-1", "worker-1", 7),
        "model-1",
        "{}",
        "a".repeat(64)
    );

    assertThat(traceId).isNull();
    assertThat(execution.getStatus())
        .isEqualTo(AgentExecutionStatus.WAITING_HUMAN);
    assertThat(execution.getNextPollAt())
        .isEqualTo(intervention.getExpiresAt());
    assertThat(execution.getLeaseOwner()).isNull();
    assertThat(intervention.getStatus())
        .isEqualTo(AgentHumanInterventionStatus.WAITING);
    assertThat(intervention.getWaitingAt()).isNotNull();
    verifyNoInteractions(traceRepository);
  }

  @Test
  void checkpointsInvalidSkillArgumentsWithoutFailingAgent() {
    AiAgentExecution execution = new AiAgentExecution();
    execution.setId("execution-1");
    execution.setAgentId("agent-1");
    execution.setAgentVersionId("agent-version-1");
    execution.setScopeType(AiResourceScope.SYSTEM);
    execution.setStatus(AgentExecutionStatus.RUNNING);
    execution.setLeaseOwner("worker-1");
    execution.setLeaseToken(7);
    execution.setLeaseExpiresAt(Instant.now().plusSeconds(60));
    when(executionRepository.findActiveByIdForUpdate("execution-1"))
        .thenReturn(Optional.of(execution));
    when(traceRepository.countActiveByExecutionId("execution-1"))
        .thenReturn(0L);
    when(traceRepository.save(any(AiAgentExecutionTrace.class)))
        .thenAnswer(invocation -> {
          AiAgentExecutionTrace trace = invocation.getArgument(0);
          trace.setId("trace-1");
          return trace;
        });

    boolean continued = coordinator.rejectSkillArguments(
        new ExecutionTask("execution-1", "worker-1", 7),
        new SkillFailureCheckpoint(
            "binding-1",
            "skill-1",
            "skill-version-1",
            "echo",
            "call-1",
            "a".repeat(64),
            "{\"uri\":\"skill/echo\"}",
            "AGENT_SKILL_ARGUMENTS_INVALID",
            "uri must match document://{documentId}",
            "b".repeat(64),
            "{\"error\":true}",
            "[{\"role\":\"TOOL\"}]",
            null,
            0,
            null
        )
    );

    assertThat(continued).isTrue();
    assertThat(execution.getStatus()).isEqualTo(AgentExecutionStatus.RUNNING);
    assertThat(execution.getConversationJson())
        .isEqualTo("[{\"role\":\"TOOL\"}]");
    assertThat(execution.getLeaseOwner()).isEqualTo("worker-1");
    assertThat(execution.getLeaseExpiresAt()).isAfter(Instant.now());
    ArgumentCaptor<AiAgentExecutionTrace> trace =
        ArgumentCaptor.forClass(AiAgentExecutionTrace.class);
    verify(traceRepository).save(trace.capture());
    assertThat(trace.getValue().getStatus())
        .isEqualTo(AgentTraceStatus.FAILED);
    assertThat(trace.getValue().getSkillExecutionId()).isNull();
    assertThat(trace.getValue().getErrorCode())
        .isEqualTo("AGENT_SKILL_ARGUMENTS_INVALID");
  }

  @Test
  void renewsOnlyTheCurrentFencedLease() {
    AiAgentExecution execution = new AiAgentExecution();
    execution.setId("execution-1");
    execution.setStatus(AgentExecutionStatus.RUNNING);
    execution.setLeaseOwner("worker-1");
    execution.setLeaseToken(7);
    execution.setLeaseExpiresAt(Instant.now().plusSeconds(10));
    Instant previousExpiry = execution.getLeaseExpiresAt();
    when(executionRepository.findActiveByIdForUpdate("execution-1"))
        .thenReturn(Optional.of(execution));

    boolean renewed = coordinator.renewLease(
        new ExecutionTask("execution-1", "worker-1", 7)
    );

    assertThat(renewed).isTrue();
    assertThat(execution.getLeaseExpiresAt()).isAfter(previousExpiry);
    verify(executionRepository).save(execution);
  }

  @Test
  void rejectsHeartbeatFromStaleFencedWorker() {
    AiAgentExecution execution = new AiAgentExecution();
    execution.setId("execution-1");
    execution.setStatus(AgentExecutionStatus.RUNNING);
    execution.setLeaseOwner("worker-2");
    execution.setLeaseToken(8);
    execution.setLeaseExpiresAt(Instant.now().plusSeconds(10));
    when(executionRepository.findActiveByIdForUpdate("execution-1"))
        .thenReturn(Optional.of(execution));

    boolean renewed = coordinator.renewLease(
        new ExecutionTask("execution-1", "worker-1", 7)
    );

    assertThat(renewed).isFalse();
    verify(executionRepository, never()).save(execution);
  }

  @Test
  void fencesAndClosesAnInFlightModelTraceAfterLeaseExpiry() {
    Instant expiredAt = Instant.now().minusSeconds(1);
    AiAgentExecution execution = new AiAgentExecution();
    execution.setId("execution-1");
    execution.setStatus(AgentExecutionStatus.RUNNING);
    execution.setAttemptCount(1);
    execution.setLeaseOwner("worker-1");
    execution.setLeaseToken(7);
    execution.setLeaseExpiresAt(expiredAt);
    execution.setCurrentTraceId("trace-1");
    execution.setCurrentModelId("model-1");
    AiAgentExecutionTrace trace = new AiAgentExecutionTrace();
    trace.setId("trace-1");
    trace.setExecutionId("execution-1");
    trace.setType(AgentTraceType.MODEL);
    trace.setStatus(AgentTraceStatus.RUNNING);
    when(executionRepository.findClaimableForUpdate(any(), any()))
        .thenReturn(List.of(execution));
    when(traceRepository.findActiveById("trace-1"))
        .thenReturn(Optional.of(trace));

    List<ExecutionTask> tasks = coordinator.claim("worker-2", 1);

    assertThat(tasks).containsExactly(
        new ExecutionTask("execution-1", "worker-2", 8)
    );
    assertThat(execution.getStatus()).isEqualTo(AgentExecutionStatus.RUNNING);
    assertThat(execution.getCurrentTraceId()).isNull();
    assertThat(execution.getCurrentModelId()).isNull();
    assertThat(execution.getLeaseOwner()).isEqualTo("worker-2");
    assertThat(execution.getLeaseToken()).isEqualTo(8);
    assertThat(trace.getStatus()).isEqualTo(AgentTraceStatus.FAILED);
    assertThat(trace.getErrorCode())
        .isEqualTo("AGENT_RUNTIME_LEASE_EXPIRED");
    assertThat(trace.getCompletedAt()).isNotNull();
    verify(traceRepository).save(trace);
  }
}
