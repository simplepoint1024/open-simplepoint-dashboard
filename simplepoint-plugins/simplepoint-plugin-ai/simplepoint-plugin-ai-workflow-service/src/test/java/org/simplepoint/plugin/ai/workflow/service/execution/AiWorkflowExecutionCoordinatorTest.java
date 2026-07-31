package org.simplepoint.plugin.ai.workflow.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowExecution;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionStatus;
import org.simplepoint.plugin.ai.workflow.api.properties.WorkflowExecutionProperties;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowExecutionRepository;
import org.simplepoint.plugin.ai.workflow.service.execution.AiWorkflowExecutionCoordinator.ExecutionTask;
import org.springframework.data.domain.Pageable;

class AiWorkflowExecutionCoordinatorTest {

  private AiWorkflowExecutionRepository repository;

  private AiWorkflowExecutionCoordinator coordinator;

  @BeforeEach
  void setUp() {
    repository = mock(AiWorkflowExecutionRepository.class);
    WorkflowExecutionProperties properties =
        new WorkflowExecutionProperties();
    properties.setBatchSize(4);
    properties.setLeaseDuration(Duration.ofMinutes(2));
    when(repository.save(any(AiWorkflowExecution.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    coordinator = new AiWorkflowExecutionCoordinator(
        repository,
        mock(AiWorkflowExecutionEventPublisher.class),
        properties
    );
  }

  @Test
  void claimsOnlyAvailableCapacityAndAllocatesFencingToken() {
    AiWorkflowExecution execution = execution();
    when(repository.findClaimableForUpdate(
        any(Instant.class),
        any(Pageable.class)
    )).thenReturn(List.of(execution));

    List<ExecutionTask> tasks = coordinator.claim("worker-1", 1);

    assertThat(tasks).singleElement().satisfies(task -> {
      assertThat(task.executionId()).isEqualTo("execution-1");
      assertThat(task.workerId()).isEqualTo("worker-1");
      assertThat(task.leaseToken()).isEqualTo(1L);
    });
    assertThat(execution.getStatus())
        .isEqualTo(WorkflowExecutionStatus.RUNNING);
    assertThat(execution.getLeaseOwner()).isEqualTo("worker-1");
    assertThat(execution.getLeaseExpiresAt()).isAfter(Instant.now());
    verify(repository).save(execution);
  }

  @Test
  void renewsOnlyCurrentFencedLease() {
    AiWorkflowExecution execution = execution();
    execution.setStatus(WorkflowExecutionStatus.RUNNING);
    execution.setLeaseOwner("worker-1");
    execution.setLeaseToken(7L);
    execution.setLeaseExpiresAt(Instant.now().plusSeconds(10));
    Instant previous = execution.getLeaseExpiresAt();
    when(repository.findActiveByIdForUpdate("execution-1"))
        .thenReturn(Optional.of(execution));

    boolean renewed = coordinator.renewLease(
        new ExecutionTask(
            "execution-1",
            "worker-1",
            7L,
            WorkflowExecutionStatus.RUNNING
        )
    );

    assertThat(renewed).isTrue();
    assertThat(execution.getLeaseExpiresAt()).isAfter(previous);
    verify(repository).save(execution);
  }

  @Test
  void fencesStaleWorkerHeartbeat() {
    AiWorkflowExecution execution = execution();
    execution.setStatus(WorkflowExecutionStatus.RUNNING);
    execution.setLeaseOwner("worker-2");
    execution.setLeaseToken(8L);
    execution.setLeaseExpiresAt(Instant.now().plusSeconds(10));
    when(repository.findActiveByIdForUpdate("execution-1"))
        .thenReturn(Optional.of(execution));

    boolean renewed = coordinator.renewLease(
        new ExecutionTask(
            "execution-1",
            "worker-1",
            7L,
            WorkflowExecutionStatus.RUNNING
        )
    );

    assertThat(renewed).isFalse();
    verify(repository, never()).save(execution);
  }

  private static AiWorkflowExecution execution() {
    AiWorkflowExecution execution = new AiWorkflowExecution();
    execution.setId("execution-1");
    execution.setStatus(WorkflowExecutionStatus.PENDING);
    execution.setAttemptCount(0);
    execution.setLeaseToken(0L);
    return execution;
  }
}
