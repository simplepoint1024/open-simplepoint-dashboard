package org.simplepoint.plugin.ai.agent.service.execution;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
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
import org.simplepoint.plugin.ai.agent.api.properties.AgentExecutionProperties;
import org.simplepoint.plugin.ai.agent.service.execution.AiAgentExecutionCoordinator.ExecutionTask;
import org.simplepoint.plugin.ai.core.service.schedule.AiIdlePollBackoff;
import org.simplepoint.plugin.ai.core.service.schedule.AiPollingProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls durable Agent work and executes reasoning loops on virtual threads.
 */
@Slf4j
@Component
public class AiAgentExecutionWorker implements DisposableBean {

  private final AiAgentExecutionCoordinator coordinator;

  private final AiAgentExecutionRunner runner;

  private final AgentExecutionProperties properties;

  private final AiIdlePollBackoff idleBackoff;

  private final ExecutorService executor;

  private final ScheduledExecutorService heartbeatExecutor;

  private final String workerId;

  private final AtomicBoolean accepting = new AtomicBoolean(true);

  private final AtomicInteger activeTasks = new AtomicInteger();

  /**
   * Creates the horizontally scalable Agent worker.
   */
  @Autowired
  public AiAgentExecutionWorker(
      final AiAgentExecutionCoordinator coordinator,
      final AiAgentExecutionRunner runner,
      final AgentExecutionProperties properties,
      final AiPollingProperties pollingProperties,
      final MeterRegistry meterRegistry
  ) {
    this(
        coordinator,
        runner,
        properties,
        pollingProperties,
        meterRegistry,
        Executors.newVirtualThreadPerTaskExecutor(),
        Executors.newSingleThreadScheduledExecutor(),
        workerId()
    );
  }

  AiAgentExecutionWorker(
      final AiAgentExecutionCoordinator coordinator,
      final AiAgentExecutionRunner runner,
      final AgentExecutionProperties properties,
      final AiPollingProperties pollingProperties,
      final MeterRegistry meterRegistry,
      final ExecutorService executor,
      final ScheduledExecutorService heartbeatExecutor,
      final String workerId
  ) {
    this.coordinator = coordinator;
    this.runner = runner;
    this.properties = properties;
    this.idleBackoff = new AiIdlePollBackoff(
        properties.getPollInterval(),
        pollingProperties
    );
    this.executor = executor;
    this.heartbeatExecutor = heartbeatExecutor;
    this.workerId = workerId;
    Gauge.builder(
        "simplepoint.ai.agent.runtime.active.tasks",
        activeTasks,
        AtomicInteger::doubleValue
    ).register(meterRegistry);
    Gauge.builder(
        "simplepoint.ai.agent.runtime.accepting",
        accepting,
        value -> value.get() ? 1D : 0D
    ).register(meterRegistry);
  }

  /**
   * Claims work without retaining database transactions during model I/O.
   */
  @Scheduled(
      fixedDelayString =
          "${simplepoint.ai.agent.execution.poll-interval:500ms}"
  )
  public void poll() {
    if (!Boolean.TRUE.equals(properties.getEnabled())
        || !accepting.get()
        || !idleBackoff.shouldPoll()) {
      return;
    }
    int availableCapacity = maximumConcurrency() - activeTasks.get();
    if (availableCapacity <= 0) {
      return;
    }
    try {
      List<ExecutionTask> tasks = coordinator.claim(
          workerId,
          availableCapacity
      );
      idleBackoff.recordResult(!tasks.isEmpty());
      tasks.forEach(this::submit);
    } catch (RuntimeException ignored) {
      log.warn("Unable to poll Agent executions: AGENT_RUNTIME_FAILED");
    }
  }

  @Override
  public void destroy() {
    accepting.set(false);
    executor.shutdown();
    heartbeatExecutor.shutdown();
    Duration timeout = shutdownTimeout();
    long timeoutMillis = Math.max(1L, timeout.toMillis());
    long startedAt = System.nanoTime();
    try {
      if (!executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
        executor.shutdownNow();
      }
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
          System.nanoTime() - startedAt
      );
      long heartbeatWait = Math.max(1L, timeoutMillis - elapsedMillis);
      if (!heartbeatExecutor.awaitTermination(
          heartbeatWait,
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

  String currentWorkerId() {
    return workerId;
  }

  private void submit(
      final AiAgentExecutionCoordinator.ExecutionTask task
  ) {
    activeTasks.incrementAndGet();
    try {
      executor.execute(() -> execute(task));
    } catch (RejectedExecutionException ex) {
      activeTasks.decrementAndGet();
      log.warn(
          "Agent execution {} was claimed while the worker was draining",
          task.executionId()
      );
    }
  }

  private void execute(
      final AiAgentExecutionCoordinator.ExecutionTask task
  ) {
    ScheduledFuture<?> heartbeat = null;
    try {
      if (!heartbeatExecutor.isShutdown()) {
        Duration heartbeatInterval = leaseHeartbeatInterval();
        heartbeat = heartbeatExecutor.scheduleWithFixedDelay(
            () -> renew(task),
            heartbeatInterval.toMillis(),
            heartbeatInterval.toMillis(),
            TimeUnit.MILLISECONDS
        );
      }
      runner.execute(task);
    } finally {
      if (heartbeat != null) {
        heartbeat.cancel(false);
      }
      activeTasks.decrementAndGet();
    }
  }

  private void renew(
      final AiAgentExecutionCoordinator.ExecutionTask task
  ) {
    try {
      if (!coordinator.renewLease(task)) {
        log.debug(
            "Agent execution {} lease heartbeat stopped after fencing",
            task.executionId()
        );
      }
    } catch (RuntimeException ignored) {
      log.warn(
          "Unable to renew Agent execution {} lease: {}",
          task.executionId(),
          "AGENT_RUNTIME_FAILED"
      );
    }
  }

  private int maximumConcurrency() {
    Integer configured = properties.getMaximumConcurrency();
    return configured == null
        ? 8 : Math.max(1, Math.min(configured, 256));
  }

  private Duration leaseHeartbeatInterval() {
    Duration configured = properties.getLeaseHeartbeatInterval();
    Duration leaseDuration = properties.getLeaseDuration();
    Duration maximum = leaseDuration == null
        || leaseDuration.isNegative()
        || leaseDuration.isZero()
        ? Duration.ofSeconds(40) : leaseDuration.dividedBy(2);
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

}
