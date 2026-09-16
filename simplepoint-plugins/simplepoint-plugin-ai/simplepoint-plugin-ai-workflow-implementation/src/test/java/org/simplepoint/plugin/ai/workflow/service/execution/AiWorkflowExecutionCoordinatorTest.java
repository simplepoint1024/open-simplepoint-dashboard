package org.simplepoint.plugin.ai.workflow.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowExecution;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowNodeExecution;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionEventType;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionStatus;
import org.simplepoint.plugin.ai.workflow.api.properties.WorkflowExecutionProperties;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowExecutionRepository;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowNodeExecutionRepository;
import org.simplepoint.plugin.ai.workflow.service.execution.AiWorkflowExecutionCoordinator.ExecutionTask;
import org.springframework.data.domain.Pageable;

class AiWorkflowExecutionCoordinatorTest {

  private AiWorkflowExecutionRepository repository;

  private AiWorkflowExecutionEventPublisher eventPublisher;

  private AiWorkflowNodeExecutionRepository nodeRepository;

  private AiWorkflowTerminationCoordinator terminationCoordinator;

  private AiWorkflowExecutionCoordinator coordinator;

  @BeforeEach
  void setUp() {
    repository = mock(AiWorkflowExecutionRepository.class);
    WorkflowExecutionProperties properties =
        new WorkflowExecutionProperties();
    properties.setBatchSize(4);
    properties.setLeaseDuration(Duration.ofMinutes(2));
    properties.setMaxAttempts(3);
    eventPublisher = mock(AiWorkflowExecutionEventPublisher.class);
    nodeRepository = mock(AiWorkflowNodeExecutionRepository.class);
    terminationCoordinator = mock(AiWorkflowTerminationCoordinator.class);
    when(repository.save(any(AiWorkflowExecution.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    coordinator = new AiWorkflowExecutionCoordinator(
        repository,
        eventPublisher,
        nodeRepository,
        terminationCoordinator,
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
    assertThat(execution.getAttemptCount()).isEqualTo(1);
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

  @Test
  void durableWaitingAndCompensatingPollsDoNotConsumeRetryBudget() {
    AiWorkflowExecution waiting = execution();
    waiting.setId("waiting-execution");
    waiting.setStatus(WorkflowExecutionStatus.WAITING_CHILD);
    waiting.setAttemptCount(3);
    waiting.setStartedAt(Instant.now().minusSeconds(30));
    AiWorkflowExecution compensating = execution();
    compensating.setId("compensating-execution");
    compensating.setStatus(WorkflowExecutionStatus.COMPENSATING);
    compensating.setAttemptCount(3);
    compensating.setStartedAt(Instant.now().minusSeconds(30));
    when(repository.findClaimableForUpdate(
        any(Instant.class), any(Pageable.class)
    )).thenReturn(List.of(waiting, compensating));

    List<ExecutionTask> tasks = coordinator.claim("worker-1", 2);

    assertThat(tasks).extracting(ExecutionTask::claimedStatus)
        .containsExactly(
            WorkflowExecutionStatus.WAITING_CHILD,
            WorkflowExecutionStatus.COMPENSATING
    );
    assertThat(waiting.getAttemptCount()).isEqualTo(3);
    assertThat(compensating.getAttemptCount()).isEqualTo(3);
    assertThat(waiting.getStatus()).isEqualTo(WorkflowExecutionStatus.RUNNING);
    assertThat(compensating.getStatus())
        .isEqualTo(WorkflowExecutionStatus.COMPENSATING);
    verify(terminationCoordinator, never()).terminate(
        any(), any(), any(), any(), any()
    );
    verify(repository, times(2)).save(any(AiWorkflowExecution.class));
  }

  @Test
  void exhaustedExpiredRunningClaimTerminatesBeforeFailing() {
    AiWorkflowExecution execution = execution();
    execution.setStatus(WorkflowExecutionStatus.RUNNING);
    execution.setAttemptCount(3);
    execution.setStartedAt(Instant.now().minusSeconds(60));
    execution.setScopeType(
        org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM
    );
    AiWorkflowNodeExecution node = new AiWorkflowNodeExecution();
    node.setId("node-execution-1");
    node.setExecutionId(execution.getId());
    node.setNodeId("node-1");
    node.setNodeOrder(0);
    node.setNodeType("wait");
    node.setStatus(
        org.simplepoint.plugin.ai.workflow.api.model
            .WorkflowNodeExecutionStatus.WAITING
    );
    when(repository.findClaimableForUpdate(
        any(Instant.class), any(Pageable.class)
    )).thenReturn(List.of(execution));
    when(nodeRepository.findAllActiveByExecutionId("execution-1"))
        .thenReturn(List.of(node));

    List<ExecutionTask> tasks = coordinator.claim("worker-1", 1);

    assertThat(tasks).isEmpty();
    assertThat(execution.getStatus()).isEqualTo(WorkflowExecutionStatus.FAILED);
    assertThat(execution.getErrorCode())
        .isEqualTo("WORKFLOW_RETRY_EXHAUSTED");
    verify(terminationCoordinator).terminate(
        org.mockito.ArgumentMatchers.eq(execution),
        org.mockito.ArgumentMatchers.eq(List.of(node)),
        org.mockito.ArgumentMatchers.eq("workflow-runtime"),
        org.mockito.ArgumentMatchers.eq("WORKFLOW_RETRY_EXHAUSTED"),
        any(Instant.class)
    );
    verify(eventPublisher).publish(
        org.mockito.ArgumentMatchers.eq(execution),
        org.mockito.ArgumentMatchers.eq(
            WorkflowExecutionEventType.EXECUTION_FAILED
        ),
        any(),
        any(),
        org.mockito.ArgumentMatchers.eq("workflow-runtime"),
        any(),
        any(Instant.class)
    );
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
