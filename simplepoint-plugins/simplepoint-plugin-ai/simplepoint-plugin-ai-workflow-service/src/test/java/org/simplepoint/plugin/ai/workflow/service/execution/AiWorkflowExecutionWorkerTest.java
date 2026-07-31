package org.simplepoint.plugin.ai.workflow.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionStatus;
import org.simplepoint.plugin.ai.workflow.api.properties.WorkflowExecutionProperties;
import org.simplepoint.plugin.ai.workflow.service.execution.AiWorkflowExecutionCoordinator.ExecutionTask;

class AiWorkflowExecutionWorkerTest {

  @Test
  void boundsConcurrencyRenewsLeaseAndDrains() throws Exception {
    final AiWorkflowExecutionCoordinator coordinator =
        mock(AiWorkflowExecutionCoordinator.class);
    final AiWorkflowExecutionEngine engine =
        mock(AiWorkflowExecutionEngine.class);
    WorkflowExecutionProperties properties =
        new WorkflowExecutionProperties();
    properties.setEnabled(true);
    properties.setMaximumConcurrency(1);
    properties.setLeaseDuration(Duration.ofMillis(200));
    properties.setLeaseHeartbeatInterval(Duration.ofMillis(20));
    properties.setShutdownTimeout(Duration.ofSeconds(1));
    ExecutionTask task = new ExecutionTask(
        "execution-1",
        "worker-test",
        7L,
        WorkflowExecutionStatus.PENDING
    );
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(coordinator.claim("worker-test", 1))
        .thenReturn(List.of(task));
    when(coordinator.renewLease(task)).thenReturn(true);
    doAnswer(invocation -> {
      started.countDown();
      release.await(1, TimeUnit.SECONDS);
      return null;
    }).when(engine).advance(task);
    AiWorkflowExecutionWorker worker =
        new AiWorkflowExecutionWorker(
            coordinator,
            engine,
            properties,
            new SimpleMeterRegistry(),
            Executors.newVirtualThreadPerTaskExecutor(),
            Executors.newSingleThreadScheduledExecutor(),
            "worker-test"
        );

    try {
      worker.poll();
      assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(worker.activeTaskCount()).isEqualTo(1);

      worker.poll();

      verify(coordinator).claim("worker-test", 1);
      verify(coordinator, timeout(1000).atLeastOnce()).renewLease(task);
      release.countDown();
      awaitIdle(worker);
    } finally {
      release.countDown();
      worker.destroy();
    }

    assertThat(worker.isAccepting()).isFalse();
    assertThat(worker.activeTaskCount()).isZero();
  }

  private static void awaitIdle(
      final AiWorkflowExecutionWorker worker
  ) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (worker.activeTaskCount() > 0
        && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
  }
}
