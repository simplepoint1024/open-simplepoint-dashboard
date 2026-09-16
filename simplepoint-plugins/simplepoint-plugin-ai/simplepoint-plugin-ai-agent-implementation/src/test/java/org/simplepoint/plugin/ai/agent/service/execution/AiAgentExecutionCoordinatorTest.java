package org.simplepoint.plugin.ai.agent.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecution;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecutionTrace;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentHumanIntervention;
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
import org.simplepoint.plugin.ai.agent.service.execution.AiAgentExecutionCoordinator.ExecutionTask;
import org.simplepoint.plugin.ai.agent.service.execution.AiAgentExecutionCoordinator.SkillFailureCheckpoint;
import org.simplepoint.plugin.ai.agent.service.execution.AiAgentExecutionCoordinator.SkillLaunchCheckpoint;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecution;
import org.simplepoint.plugin.ai.skill.api.model.SkillAgentExecutionCommand;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillExecutionService;

class AiAgentExecutionCoordinatorTest {

  private AiAgentExecutionRepository executionRepository;

  private AiAgentExecutionTraceRepository traceRepository;

  private AiAgentHumanInterventionRepository interventionRepository;

  private AiAgentActiveTraceCancellation activeTraceCancellation;

  private AiSkillExecutionService skillExecutionService;

  private AiAgentExecutionCoordinator coordinator;

  @BeforeEach
  void setUp() {
    executionRepository = mock(AiAgentExecutionRepository.class);
    traceRepository = mock(AiAgentExecutionTraceRepository.class);
    interventionRepository = mock(AiAgentHumanInterventionRepository.class);
    activeTraceCancellation = mock(AiAgentActiveTraceCancellation.class);
    skillExecutionService = mock(AiSkillExecutionService.class);
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
        activeTraceCancellation,
        skillExecutionService,
        new AgentExecutionProperties()
    );
  }

  @Test
  void doesNotCreateChildAfterAgentWasCancelled() {
    AiAgentExecution execution = ownedExecution();
    execution.setStatus(AgentExecutionStatus.CANCELLED);
    when(executionRepository.findActiveByIdForUpdate("execution-1"))
        .thenReturn(Optional.of(execution));

    assertThatThrownBy(() -> coordinator.launchSkill(
        task(),
        launchCheckpoint()
    )).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lease is no longer owned");

    verifyNoInteractions(skillExecutionService);
    verify(traceRepository, never()).save(any());
  }

  @Test
  void doesNotCreateChildAfterAgentLeaseExpired() {
    AiAgentExecution execution = ownedExecution();
    execution.setLeaseExpiresAt(Instant.now().minusSeconds(1));
    when(executionRepository.findActiveByIdForUpdate("execution-1"))
        .thenReturn(Optional.of(execution));

    assertThatThrownBy(() -> coordinator.launchSkill(
        task(),
        launchCheckpoint()
    )).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lease is no longer owned");

    verifyNoInteractions(skillExecutionService);
    verify(traceRepository, never()).save(any());
  }

  @Test
  void atomicallyAssociatesPinnedChildAndReleasesAgentLease() {
    AiAgentExecution execution = ownedExecution();
    AiSkillExecution child = pinnedChild();
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
    when(skillExecutionService.startVersionForAgent(any()))
        .thenReturn(child);

    boolean launched = coordinator.launchSkill(task(), launchCheckpoint());

    assertThat(launched).isTrue();
    assertThat(execution.getStatus())
        .isEqualTo(AgentExecutionStatus.WAITING_SKILL);
    assertThat(execution.getCurrentSkillBindingId()).isEqualTo("binding-1");
    assertThat(execution.getCurrentSkillExecutionId())
        .isEqualTo("skill-execution-1");
    assertThat(execution.getCurrentToolCallId()).isEqualTo("call-1");
    assertThat(execution.getCurrentTraceId()).isEqualTo("trace-1");
    assertThat(execution.getNextPollAt()).isAfter(Instant.now());
    assertThat(execution.getLeaseOwner()).isNull();
    assertThat(execution.getLeaseExpiresAt()).isNull();
    ArgumentCaptor<SkillAgentExecutionCommand> command =
        ArgumentCaptor.forClass(SkillAgentExecutionCommand.class);
    verify(skillExecutionService).startVersionForAgent(command.capture());
    assertThat(command.getValue().skillId()).isEqualTo("skill-1");
    assertThat(command.getValue().skillVersionId())
        .isEqualTo("skill-version-1");
    assertThat(command.getValue().idempotencyKey())
        .isEqualTo("execution-1:call-1");
    assertThat(command.getValue().executionScope())
        .isEqualTo(AiResourceScope.TENANT);
    assertThat(command.getValue().tenantId()).isEqualTo("tenant-1");
    assertThat(command.getValue().requestedBy()).isEqualTo("user-1");
    assertThat(command.getValue().input())
        .containsEntry("message", "hello");
    ArgumentCaptor<AiAgentExecutionTrace> trace =
        ArgumentCaptor.forClass(AiAgentExecutionTrace.class);
    verify(traceRepository).save(trace.capture());
    assertThat(trace.getValue().getStatus())
        .isEqualTo(AgentTraceStatus.RUNNING);
    assertThat(trace.getValue().getSkillExecutionId())
        .isEqualTo("skill-execution-1");
    assertThat(trace.getValue().getToolCallId()).isEqualTo("call-1");
  }

  @Test
  void repeatedLaunchOfSameCheckpointIsIdempotent() {
    AiAgentExecution execution = ownedExecution();
    AiSkillExecution child = pinnedChild();
    AtomicReference<AiAgentExecutionTrace> savedTrace =
        new AtomicReference<>();
    when(executionRepository.findActiveByIdForUpdate("execution-1"))
        .thenReturn(Optional.of(execution));
    when(traceRepository.countActiveByExecutionId("execution-1"))
        .thenReturn(0L);
    when(traceRepository.save(any(AiAgentExecutionTrace.class)))
        .thenAnswer(invocation -> {
          AiAgentExecutionTrace trace = invocation.getArgument(0);
          trace.setId("trace-1");
          savedTrace.set(trace);
          return trace;
        });
    when(traceRepository.findActiveById("trace-1"))
        .thenAnswer(invocation -> Optional.ofNullable(savedTrace.get()));
    when(skillExecutionService.startVersionForAgent(any()))
        .thenReturn(child);

    assertThat(coordinator.launchSkill(task(), launchCheckpoint())).isTrue();
    assertThat(coordinator.launchSkill(task(), launchCheckpoint())).isTrue();

    verify(skillExecutionService, times(1)).startVersionForAgent(any());
    verify(traceRepository, times(1)).save(any());
    verify(executionRepository, times(1)).save(execution);
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
  void expiredHumanInterventionCancelClosesActiveTraceAtRuntime() {
    AiAgentExecution execution = new AiAgentExecution();
    execution.setId("execution-1");
    execution.setAgentId("agent-1");
    execution.setScopeType(AiResourceScope.SYSTEM);
    execution.setStatus(AgentExecutionStatus.WAITING_HUMAN);
    execution.setCurrentHumanInterventionId("intervention-1");
    execution.setHumanInterventionTimeoutAction(
        AgentHumanInterventionTimeoutAction.CANCEL
    );
    AiAgentHumanIntervention intervention =
        new AiAgentHumanIntervention();
    intervention.setId("intervention-1");
    intervention.setAgentId("agent-1");
    intervention.setExecutionId("execution-1");
    intervention.setScopeType(AiResourceScope.SYSTEM);
    intervention.setStatus(AgentHumanInterventionStatus.WAITING);
    intervention.setExpiresAt(Instant.now().minusSeconds(1));
    when(executionRepository.findClaimableForUpdate(any(), any()))
        .thenReturn(List.of(execution));
    when(interventionRepository.findActiveByIdForUpdate("intervention-1"))
        .thenReturn(Optional.of(intervention));

    List<ExecutionTask> claimed = coordinator.claim("worker-1", 1);

    assertThat(claimed).isEmpty();
    assertThat(execution.getStatus())
        .isEqualTo(AgentExecutionStatus.CANCELLED);
    assertThat(execution.getCurrentHumanInterventionId()).isNull();
    assertThat(intervention.getStatus())
        .isEqualTo(AgentHumanInterventionStatus.EXPIRED);
    verify(activeTraceCancellation).cancelActiveTrace(
        org.mockito.ArgumentMatchers.eq(execution),
        org.mockito.ArgumentMatchers.eq("agent-runtime"),
        org.mockito.ArgumentMatchers.eq(
            "Agent human intervention timed out"
        ),
        any(Instant.class)
    );
    verify(executionRepository).save(execution);
  }

  @Test
  void checkpointsInvalidSkillArgumentsWithoutFailingAgent()
      throws Exception {
    String sentinel =
        "provider \"token\"=sk-live-sentinel\\internal\n"
            + "https://provider.example/schema";
    String outputJson = JsonMapper.builder().build()
        .writeValueAsString(Map.of("error", true, "message", sentinel));
    final String conversationJson = JsonMapper.builder().build()
        .writeValueAsString(List.of(Map.of(
            "role", "TOOL",
            "content", outputJson
        )));
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
            sentinel,
            "b".repeat(64),
            outputJson,
            conversationJson,
            null,
            0,
            "c".repeat(64)
        )
    );

    assertThat(continued).isTrue();
    assertThat(execution.getStatus()).isEqualTo(AgentExecutionStatus.RUNNING);
    assertThat(execution.getConversationJson())
        .doesNotContain("sk-live-sentinel")
        .contains("AGENT_SKILL_ARGUMENTS_INVALID");
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
    assertThat(trace.getValue().getErrorMessage())
        .isEqualTo("AGENT_SKILL_ARGUMENTS_INVALID");
    assertThat(trace.getValue().getOutputJson())
        .doesNotContain("sk-live-sentinel")
        .contains("AGENT_SKILL_ARGUMENTS_INVALID");
    assertThat(trace.getValue().getResponseHash()).isNull();
    assertThat(execution.getMemorySummaryHash()).isNull();
  }

  @Test
  void persistsOnlyStableDiagnosticsForRuntimeFailures() {
    final String sentinel =
        "provider body Bearer sk-live-secret at https://internal.example";
    AiAgentExecution execution = ownedExecution();
    AiAgentExecutionTrace trace = new AiAgentExecutionTrace();
    trace.setId("trace-1");
    trace.setExecutionId(execution.getId());
    trace.setStatus(AgentTraceStatus.RUNNING);
    when(executionRepository.findActiveByIdForUpdate("execution-1"))
        .thenReturn(Optional.of(execution));
    when(traceRepository.findActiveById("trace-1"))
        .thenReturn(Optional.of(trace));

    assertThat(coordinator.failModelAttempt(
        task(),
        "trace-1",
        "PROVIDER_" + sentinel,
        sentinel
    )).isTrue();
    coordinator.fail(task(), "INTERNAL_" + sentinel, sentinel);

    assertThat(trace.getId()).isEqualTo("trace-1");
    assertThat(trace.getErrorCode()).isEqualTo("AGENT_RUNTIME_FAILED");
    assertThat(trace.getErrorMessage()).isEqualTo("AGENT_RUNTIME_FAILED");
    assertThat(execution.getId()).isEqualTo("execution-1");
    assertThat(execution.getErrorCode())
        .isEqualTo("AGENT_EXECUTION_FAILED");
    assertThat(execution.getErrorMessage())
        .isEqualTo("AGENT_EXECUTION_FAILED");
    assertThat(trace.getErrorCode() + trace.getErrorMessage()
        + execution.getErrorCode() + execution.getErrorMessage())
        .doesNotContain(sentinel);
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

  private AiAgentExecution ownedExecution() {
    AiAgentExecution execution = new AiAgentExecution();
    execution.setId("execution-1");
    execution.setAgentId("agent-1");
    execution.setAgentVersionId("agent-version-1");
    execution.setScopeType(AiResourceScope.TENANT);
    execution.setTenantId("tenant-1");
    execution.setRequestedBy("user-1");
    execution.setStatus(AgentExecutionStatus.RUNNING);
    execution.setLeaseOwner("worker-1");
    execution.setLeaseToken(7);
    execution.setLeaseExpiresAt(Instant.now().plusSeconds(60));
    return execution;
  }

  private ExecutionTask task() {
    return new ExecutionTask("execution-1", "worker-1", 7);
  }

  private SkillLaunchCheckpoint launchCheckpoint() {
    return new SkillLaunchCheckpoint(
        "binding-1",
        "skill-1",
        "skill-version-1",
        "b".repeat(64),
        "echo",
        "call-1",
        "a".repeat(64),
        "{\"message\":\"hello\"}",
        Map.of("message", "hello")
    );
  }

  private AiSkillExecution pinnedChild() {
    AiSkillExecution child = new AiSkillExecution();
    child.setId("skill-execution-1");
    child.setSkillId("skill-1");
    child.setSkillVersionId("skill-version-1");
    child.setScopeType(AiResourceScope.TENANT);
    child.setTenantId("tenant-1");
    return child;
  }
}
