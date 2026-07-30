package org.simplepoint.plugin.ai.skill.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecution;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecutionStep;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStatus;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStepStatus;
import org.simplepoint.plugin.ai.skill.api.properties.SkillExecutionProperties;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillExecutionRepository;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillExecutionStepRepository;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class AiSkillExecutionCoordinatorTest {

  @Mock
  private AiSkillExecutionRepository executionRepository;

  @Mock
  private AiSkillExecutionStepRepository stepRepository;

  private AiSkillExecutionCoordinator coordinator;

  @BeforeEach
  void setUp() {
    coordinator = new AiSkillExecutionCoordinator(
        executionRepository,
        stepRepository,
        new SkillExecutionProperties()
    );
  }

  @Test
  void claimsPendingExecutionWithMonotonicLeaseToken() {
    AiSkillExecution execution = execution();
    AiSkillExecutionStep step = step();
    when(executionRepository.findClaimableForUpdate(
        any(),
        any(Pageable.class)
    )).thenReturn(List.of(execution));
    when(stepRepository.findAllActiveByExecutionId("execution-a"))
        .thenReturn(List.of(step));

    var tasks = coordinator.claim("worker-a");

    assertThat(tasks).singleElement().satisfies(task -> {
      assertThat(task.workerId()).isEqualTo("worker-a");
      assertThat(task.leaseToken()).isEqualTo(1);
      assertThat(task.steps()).singleElement()
          .extracting("snapshotId")
          .isEqualTo("snapshot-a");
    });
    assertThat(execution.getStatus()).isEqualTo(SkillExecutionStatus.RUNNING);
    assertThat(execution.getAttemptCount()).isEqualTo(1);
    assertThat(execution.getLeaseExpiresAt()).isNotNull();
    verify(executionRepository).save(execution);
  }

  @Test
  void rejectsStepCheckpointFromStaleWorker() {
    AiSkillExecution execution = execution();
    execution.setStatus(SkillExecutionStatus.RUNNING);
    execution.setLeaseOwner("worker-new");
    execution.setLeaseToken(2);
    when(executionRepository.findActiveByIdForUpdate("execution-a"))
        .thenReturn(Optional.of(execution));
    var stale = new AiSkillExecutionCoordinator.ExecutionTask(
        "execution-a",
        "skill-a",
        "version-a",
        AiResourceScope.SYSTEM,
        null,
        "user-a",
        "{}",
        null,
        null,
        "{\"type\":\"object\"}",
        1,
        300,
        1024L * 1024L,
        0,
        2,
        Instant.now().plusSeconds(300),
        "worker-old",
        1,
        List.of()
    );

    assertThatThrownBy(() ->
        coordinator.beginStep(stale, "step-a", "{}", "a".repeat(64)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stale");
  }

  @Test
  void rejectsToolCallAfterExecutionBudgetIsExhausted() {
    AiSkillExecution execution = execution();
    execution.setStatus(SkillExecutionStatus.RUNNING);
    execution.setLeaseOwner("worker-a");
    execution.setLeaseToken(1);
    execution.setConsumedToolCalls(1);
    AiSkillExecutionStep step = step();
    when(executionRepository.findActiveByIdForUpdate("execution-a"))
        .thenReturn(Optional.of(execution));
    when(stepRepository.findActiveByExecutionIdAndStepIdForUpdate(
        "execution-a",
        "step-a"
    )).thenReturn(Optional.of(step));
    var task = new AiSkillExecutionCoordinator.ExecutionTask(
        "execution-a",
        "skill-a",
        "version-a",
        AiResourceScope.SYSTEM,
        null,
        "user-a",
        "{}",
        null,
        null,
        "{\"type\":\"object\"}",
        1,
        300,
        1024L * 1024L,
        1,
        2,
        execution.getDeadlineAt(),
        "worker-a",
        1,
        List.of()
    );

    assertThatThrownBy(() ->
        coordinator.beginStep(task, "step-a", "{}", "a".repeat(64)))
        .isInstanceOf(SkillExecutionBudgetExceededException.class)
        .hasMessageContaining("Tool call budget");
  }

  @Test
  void pausesBeforeStartingTheNextToolCheckpoint() {
    AiSkillExecution execution = execution();
    execution.setStatus(SkillExecutionStatus.RUNNING);
    execution.setLeaseOwner("worker-a");
    execution.setLeaseToken(1);
    execution.setPauseRequested(true);
    when(executionRepository.findActiveByIdForUpdate("execution-a"))
        .thenReturn(Optional.of(execution));
    var task = new AiSkillExecutionCoordinator.ExecutionTask(
        "execution-a",
        "skill-a",
        "version-a",
        AiResourceScope.SYSTEM,
        null,
        "user-a",
        "{}",
        null,
        null,
        "{\"type\":\"object\"}",
        1,
        300,
        1024L * 1024L,
        0,
        2,
        execution.getDeadlineAt(),
        "worker-a",
        1,
        List.of()
    );

    boolean started = coordinator.beginStep(
        task,
        "step-a",
        "{}",
        "a".repeat(64)
    );

    assertThat(started).isFalse();
    assertThat(execution.getStatus()).isEqualTo(SkillExecutionStatus.PAUSED);
    assertThat(execution.getPausedAt()).isNotNull();
    assertThat(execution.getInactiveSince()).isNotNull();
    assertThat(execution.getLeaseOwner()).isNull();
  }

  private static AiSkillExecution execution() {
    AiSkillExecution execution = new AiSkillExecution();
    execution.setId("execution-a");
    execution.setSkillId("skill-a");
    execution.setSkillVersionId("version-a");
    execution.setScopeType(AiResourceScope.SYSTEM);
    execution.setStatus(SkillExecutionStatus.PENDING);
    execution.setAttemptCount(0);
    execution.setLeaseToken(0);
    execution.setInputJson("{}");
    execution.setOutputSchemaJson("{\"type\":\"object\"}");
    execution.setMaximumToolCalls(1);
    execution.setMaximumDurationSeconds(300);
    execution.setMaximumPayloadBytes(1024L * 1024L);
    execution.setConsumedToolCalls(0);
    execution.setConsumedPayloadBytes(2L);
    execution.setDeadlineAt(Instant.now().plusSeconds(300));
    return execution;
  }

  private static AiSkillExecutionStep step() {
    AiSkillExecutionStep step = new AiSkillExecutionStep();
    step.setId("step-record-a");
    step.setExecutionId("execution-a");
    step.setStepId("step-a");
    step.setStepType("tool");
    step.setStepOrder(0);
    step.setStatus(SkillExecutionStepStatus.PENDING);
    step.setBindingId("binding-a");
    step.setCapabilityAlias("echo");
    step.setMcpServerId("server-a");
    step.setCapabilitySnapshotId("snapshot-a");
    step.setCapabilityName("echo");
    step.setCapabilitySchemaHash("a".repeat(64));
    step.setCapabilityTemplate(false);
    return step;
  }
}
