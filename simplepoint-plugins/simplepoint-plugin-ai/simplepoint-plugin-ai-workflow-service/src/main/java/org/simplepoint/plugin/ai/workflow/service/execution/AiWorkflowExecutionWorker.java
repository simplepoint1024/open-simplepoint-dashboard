package org.simplepoint.plugin.ai.workflow.service.execution;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.plugin.ai.workflow.api.properties.WorkflowExecutionProperties;
import org.simplepoint.plugin.ai.workflow.service.execution.AiWorkflowExecutionCoordinator.ExecutionTask;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls durable Workflow work and advances it on virtual threads.
 */
@Slf4j
@Component
public class AiWorkflowExecutionWorker implements DisposableBean {

  private final AiWorkflowExecutionCoordinator coordinator;

  private final AiWorkflowExecutionEngine engine;

  private final WorkflowExecutionProperties properties;

  private final ExecutorService executor;

  private final ScheduledExecutorService heartbeatExecutor;

  private final String workerId;

  private final AtomicBoolean accepting = new AtomicBoolean(true);

  private final AtomicInteger activeTasks = new AtomicInteger();

  /**
   * Creates the horizontally scalable Workflow worker.
   */
  @Autowired
  public AiWorkflowExecutionWorker(
      final AiWorkflowExecutionCoordinator coordinator,
      final AiWorkflowExecutionEngine engine,
      final WorkflowExecutionProperties properties,
      final MeterRegistry meterRegistry
  ) {
    this(
        coordinator,
        engine,
        properties,
        meterRegistry,
        Executors.newVirtualThreadPerTaskExecutor(),
        Executors.newSingleThreadScheduledExecutor(),
        workerId()
    );
  }

  AiWorkflowExecutionWorker(
      final AiWorkflowExecutionCoordinator coordinator,
      final AiWorkflowExecutionEngine engine,
      final WorkflowExecutionProperties properties,
      final MeterRegistry meterRegistry,
      final ExecutorService executor,
      final ScheduledExecutorService heartbeatExecutor,
      final String workerId
  ) {
    this.coordinator = coordinator;
    this.engine = engine;
    this.properties = properties;
    this.executor = executor;
    this.heartbeatExecutor = heartbeatExecutor;
    this.workerId = workerId;
    Gauge.builder(
        "simplepoint.ai.workflow.runtime.active.tasks",
        activeTasks,
        AtomicInteger::doubleValue
    ).register(meterRegistry);
    Gauge.builder(
        "simplepoint.ai.workflow.runtime.accepting",
        accepting,
        value -> value.get() ? 1D : 0D
    ).register(meterRegistry);
  }

  /**
   * Claims due work without retaining a transaction between polls.
   */
  @Scheduled(
      fixedDelayString =
          "${simplepoint.ai.workflow.execution.poll-interval:500ms}"
  )
  public void poll() {
    if (!Boolean.TRUE.equals(properties.getEnabled())
        || !accepting.get()) {
      return;
    }
    int capacity = maximumConcurrency() - activeTasks.get();
    if (capacity <= 0) {
      return;
    }
    try {
      coordinator.claim(workerId, capacity).forEach(this::submit);
    } catch (RuntimeException ex) {
      log.warn(
          "Unable to poll Workflow executions: {}",
          safeMessage(ex)
      );
    }
  }

  @Override
  public void destroy() {
    accepting.set(false);
    executor.shutdown();
    heartbeatExecutor.shutdown();
    long timeout = Math.max(1L, shutdownTimeout().toMillis());
    long started = System.nanoTime();
    try {
      if (!executor.awaitTermination(timeout, TimeUnit.MILLISECONDS)) {
        executor.shutdownNow();
      }
      long elapsed = TimeUnit.NANOSECONDS.toMillis(
          System.nanoTime() - started
      );
      long remaining = Math.max(1L, timeout - elapsed);
      if (!heartbeatExecutor.awaitTermination(
          remaining,
          TimeUnit.MILLISECONDS
      )) {
        heartbeatExecutor.shutdownNow();
      }
    } catch (InterruptedException ex) {
      executor.shutdownNow();
      heartbeatExecutor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  boolean isAccepting() {
    return accepting.get();
  }

  int activeTaskCount() {
    return activeTasks.get();
  }

  private void submit(final ExecutionTask task) {
    activeTasks.incrementAndGet();
    try {
      executor.execute(() -> execute(task));
    } catch (RejectedExecutionException ex) {
      activeTasks.decrementAndGet();
      log.warn(
          "Workflow execution {} was claimed while draining",
          task.executionId()
      );
    }
  }

  private void execute(final ExecutionTask task) {
    ScheduledFuture<?> heartbeat = null;
    try {
      Duration interval = heartbeatInterval();
      heartbeat = heartbeatExecutor.scheduleWithFixedDelay(
          () -> renew(task),
          interval.toMillis(),
          interval.toMillis(),
          TimeUnit.MILLISECONDS
      );
      engine.advance(task);
    } catch (AiWorkflowExecutionCoordinator.StaleWorkflowLeaseException ex) {
      log.debug(
          "Workflow execution {} was fenced",
          task.executionId()
      );
    } catch (RuntimeException ex) {
      log.warn(
          "Workflow execution {} turn failed: {}",
          task.executionId(),
          safeMessage(ex)
      );
    } finally {
      if (heartbeat != null) {
        heartbeat.cancel(false);
      }
      activeTasks.decrementAndGet();
    }
  }

  private void renew(final ExecutionTask task) {
    try {
      coordinator.renewLease(task);
    } catch (RuntimeException ex) {
      log.warn(
          "Unable to renew Workflow execution {} lease: {}",
          task.executionId(),
          safeMessage(ex)
      );
    }
  }

  private int maximumConcurrency() {
    Integer configured = properties.getMaximumConcurrency();
    return configured == null
        ? 8 : Math.max(1, Math.min(configured, 256));
  }

  private Duration heartbeatInterval() {
    Duration configured = properties.getLeaseHeartbeatInterval();
    Duration lease = properties.getLeaseDuration();
    Duration maximum = lease == null
        || lease.isNegative()
        || lease.isZero() ? Duration.ofSeconds(40) : lease.dividedBy(2);
    if (configured == null || configured.isNegative()
        || configured.isZero()) {
      return minimum(Duration.ofSeconds(20), maximum);
    }
    return minimum(configured, maximum);
  }

  private Duration shutdownTimeout() {
    Duration configured = properties.getShutdownTimeout();
    return configured == null || configured.isNegative()
        || configured.isZero() ? Duration.ofSeconds(30) : configured;
  }

  private static Duration minimum(
      final Duration first,
      final Duration second
  ) {
    return first.compareTo(second) <= 0 ? first : second;
  }

  private static String workerId() {
    String hostname = System.getenv("HOSTNAME");
    String node = hostname == null || hostname.isBlank()
        ? "local" : hostname.trim();
    return node + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private static String safeMessage(final RuntimeException exception) {
    String message = exception == null ? null : exception.getMessage();
    return message == null || message.isBlank()
        ? "Workflow execution failed" : message;
  }
}
