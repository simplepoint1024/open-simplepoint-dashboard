package org.simplepoint.plugin.ai.skill.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        AiResourceScope.SYSTEM,
        null,
        "user-a",
        "{}",
        null,
        "{\"type\":\"object\"}",
        "worker-old",
        1,
        List.of()
    );

    assertThatThrownBy(() ->
        coordinator.beginStep(stale, "step-a", "{}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stale");
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
    return execution;
  }

  private static AiSkillExecutionStep step() {
    AiSkillExecutionStep step = new AiSkillExecutionStep();
    step.setId("step-record-a");
    step.setExecutionId("execution-a");
    step.setStepId("step-a");
    step.setStepOrder(0);
    step.setStatus(SkillExecutionStepStatus.PENDING);
    step.setMcpServerId("server-a");
    step.setCapabilitySnapshotId("snapshot-a");
    step.setToolName("echo");
    step.setInputSchemaHash("a".repeat(64));
    return step;
  }
}
