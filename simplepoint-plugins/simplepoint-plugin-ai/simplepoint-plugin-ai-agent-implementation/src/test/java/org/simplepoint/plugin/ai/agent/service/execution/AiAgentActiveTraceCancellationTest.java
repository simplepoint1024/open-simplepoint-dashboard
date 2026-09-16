package org.simplepoint.plugin.ai.agent.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecution;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecutionTrace;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceType;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentExecutionTraceRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.skill.api.model.SkillPinnedChildCancelCommand;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillExecutionService;

class AiAgentActiveTraceCancellationTest {

  private AiAgentExecutionTraceRepository traceRepository;

  private AiSkillExecutionService skillExecutionService;

  private AiAgentActiveTraceCancellation cancellation;

  @BeforeEach
  void setUp() {
    traceRepository = mock(AiAgentExecutionTraceRepository.class);
    skillExecutionService = mock(AiSkillExecutionService.class);
    when(traceRepository.save(any(AiAgentExecutionTrace.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    cancellation = new AiAgentActiveTraceCancellation(
        traceRepository,
        skillExecutionService
    );
  }

  @Test
  void waitingSkillCascadesToExactPinnedChildAndClosesTrace() {
    AiAgentExecution execution = skillExecution();
    execution.setCurrentModelId("last-model-a");
    AiAgentExecutionTrace trace = skillTrace(AgentTraceStatus.RUNNING);
    when(traceRepository.findActiveById("trace-a"))
        .thenReturn(Optional.of(trace));
    Instant cancelledAt = Instant.now();

    cancellation.cancelActiveTrace(
        execution,
        "workflow-runtime-a",
        "Parent Workflow was cancelled",
        cancelledAt
    );

    ArgumentCaptor<SkillPinnedChildCancelCommand> command =
        ArgumentCaptor.forClass(SkillPinnedChildCancelCommand.class);
    verify(skillExecutionService).cancelPinnedChild(command.capture());
    assertThat(command.getValue().skillId()).isEqualTo("skill-a");
    assertThat(command.getValue().skillVersionId())
        .isEqualTo("skill-version-a");
    assertThat(command.getValue().executionId())
        .isEqualTo("skill-execution-a");
    assertThat(command.getValue().executionScope())
        .isEqualTo(AiResourceScope.TENANT);
    assertThat(command.getValue().tenantId()).isEqualTo("tenant-a");
    assertThat(command.getValue().idempotencyKey())
        .isEqualTo("agent-execution-a:tool-call-a");
    assertThat(command.getValue().actorId())
        .isEqualTo("workflow-runtime-a");
    assertThat(trace.getStatus()).isEqualTo(AgentTraceStatus.CANCELLED);
    assertThat(trace.getCompletedAt()).isEqualTo(cancelledAt);
    assertThat(trace.getErrorCode()).isEqualTo("AGENT_EXECUTION_CANCELLED");
    assertThat(trace.getErrorMessage())
        .isEqualTo("AGENT_EXECUTION_CANCELLED");
    verify(traceRepository).save(trace);
    assertPointersCleared(execution);
  }

  @Test
  void activeModelTraceClosesWithoutCallingSkill() {
    AiAgentExecution execution = baseExecution();
    execution.setStatus(AgentExecutionStatus.RUNNING);
    execution.setCurrentTraceId("trace-a");
    execution.setCurrentModelId("model-a");
    AiAgentExecutionTrace trace = new AiAgentExecutionTrace();
    trace.setId("trace-a");
    trace.setExecutionId("agent-execution-a");
    trace.setType(AgentTraceType.MODEL);
    trace.setStatus(AgentTraceStatus.RUNNING);
    trace.setModelDefinitionId("model-a");
    when(traceRepository.findActiveById("trace-a"))
        .thenReturn(Optional.of(trace));

    cancellation.cancelActiveTrace(
        execution,
        "operator-a",
        "Agent execution was cancelled",
        Instant.now()
    );

    assertThat(trace.getStatus()).isEqualTo(AgentTraceStatus.CANCELLED);
    assertThat(trace.getCompletedAt()).isNotNull();
    verify(traceRepository).save(trace);
    verifyNoInteractions(skillExecutionService);
    assertPointersCleared(execution);
  }

  @Test
  void terminalSkillTracePreservesChildFactsAndOnlyClearsPointers() {
    AiAgentExecution execution = skillExecution();
    AiAgentExecutionTrace trace = skillTrace(AgentTraceStatus.SUCCEEDED);
    Instant completedAt = Instant.now().minusSeconds(3);
    trace.setCompletedAt(completedAt);
    when(traceRepository.findActiveById("trace-a"))
        .thenReturn(Optional.of(trace));

    cancellation.cancelActiveTrace(
        execution,
        "operator-a",
        "Agent execution was cancelled",
        Instant.now()
    );

    assertThat(trace.getStatus()).isEqualTo(AgentTraceStatus.SUCCEEDED);
    assertThat(trace.getCompletedAt()).isEqualTo(completedAt);
    verify(traceRepository, never()).save(any());
    verifyNoInteractions(skillExecutionService);
    assertPointersCleared(execution);
  }

  @Test
  void corruptedSkillRelationshipFailsBeforeAnyCancellationMutation() {
    AiAgentExecution execution = skillExecution();
    execution.setCurrentToolCallId("other-tool-call");
    AiAgentExecutionTrace trace = skillTrace(AgentTraceStatus.RUNNING);
    when(traceRepository.findActiveById("trace-a"))
        .thenReturn(Optional.of(trace));

    assertThatThrownBy(() -> cancellation.cancelActiveTrace(
        execution,
        "operator-a",
        "Agent execution was cancelled",
        Instant.now()
    )).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("relationship is corrupted");

    assertThat(trace.getStatus()).isEqualTo(AgentTraceStatus.RUNNING);
    assertThat(execution.getCurrentTraceId()).isEqualTo("trace-a");
    assertThat(execution.getCurrentSkillExecutionId())
        .isEqualTo("skill-execution-a");
    verify(traceRepository, never()).save(any());
    verifyNoInteractions(skillExecutionService);
  }

  @Test
  void orphanSkillPointersWithoutTraceAreRejected() {
    AiAgentExecution execution = baseExecution();
    execution.setCurrentSkillExecutionId("skill-execution-a");

    assertThatThrownBy(() -> cancellation.cancelActiveTrace(
        execution,
        "operator-a",
        "Agent execution was cancelled",
        Instant.now()
    )).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("orphan active Skill pointers");

    assertThat(execution.getCurrentSkillExecutionId())
        .isEqualTo("skill-execution-a");
    verifyNoInteractions(traceRepository, skillExecutionService);
  }

  @Test
  void completedModelCheckpointWithoutTraceClearsLastModelPointer() {
    AiAgentExecution execution = baseExecution();
    execution.setCurrentModelId("last-model-a");

    cancellation.cancelActiveTrace(
        execution,
        "operator-a",
        "Agent execution was cancelled",
        Instant.now()
    );

    assertPointersCleared(execution);
    verifyNoInteractions(traceRepository, skillExecutionService);
  }

  @Test
  void traceFromAnotherExecutionIsRejectedWithoutMutation() {
    AiAgentExecution execution = skillExecution();
    AiAgentExecutionTrace trace = skillTrace(AgentTraceStatus.RUNNING);
    trace.setExecutionId("other-agent-execution");
    when(traceRepository.findActiveById("trace-a"))
        .thenReturn(Optional.of(trace));

    assertThatThrownBy(() -> cancellation.cancelActiveTrace(
        execution,
        "operator-a",
        "Agent execution was cancelled",
        Instant.now()
    )).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("escaped its execution boundary");

    verify(traceRepository, never()).save(any());
    verifyNoInteractions(skillExecutionService);
  }

  private static AiAgentExecution baseExecution() {
    AiAgentExecution execution = new AiAgentExecution();
    execution.setId("agent-execution-a");
    execution.setAgentId("agent-a");
    execution.setAgentVersionId("agent-version-a");
    execution.setScopeType(AiResourceScope.TENANT);
    execution.setTenantId("tenant-a");
    execution.setStatus(AgentExecutionStatus.WAITING_SKILL);
    return execution;
  }

  private static AiAgentExecution skillExecution() {
    AiAgentExecution execution = baseExecution();
    execution.setCurrentTraceId("trace-a");
    execution.setCurrentSkillBindingId("skill-binding-a");
    execution.setCurrentSkillExecutionId("skill-execution-a");
    execution.setCurrentToolCallId("tool-call-a");
    return execution;
  }

  private static AiAgentExecutionTrace skillTrace(
      final AgentTraceStatus status
  ) {
    AiAgentExecutionTrace trace = new AiAgentExecutionTrace();
    trace.setId("trace-a");
    trace.setExecutionId("agent-execution-a");
    trace.setType(AgentTraceType.SKILL);
    trace.setStatus(status);
    trace.setSkillBindingId("skill-binding-a");
    trace.setSkillId("skill-a");
    trace.setSkillVersionId("skill-version-a");
    trace.setSkillExecutionId("skill-execution-a");
    trace.setToolCallId("tool-call-a");
    return trace;
  }

  private static void assertPointersCleared(
      final AiAgentExecution execution
  ) {
    assertThat(execution.getCurrentTraceId()).isNull();
    assertThat(execution.getCurrentModelId()).isNull();
    assertThat(execution.getCurrentSkillBindingId()).isNull();
    assertThat(execution.getCurrentSkillExecutionId()).isNull();
    assertThat(execution.getCurrentToolCallId()).isNull();
  }
}
