package org.simplepoint.plugin.ai.mcp.service.task;

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
import org.simplepoint.plugin.ai.core.service.schedule.AiIdlePollBackoff;
import org.simplepoint.plugin.ai.core.service.schedule.AiPollingProperties;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallResult;
import org.simplepoint.plugin.ai.mcp.api.properties.AiMcpProperties;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpPublicationRuntimeService;
import org.simplepoint.plugin.ai.mcp.service.task.AiMcpTaskServiceImpl.ExecutionToken;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Horizontally scalable durable MCP Task worker.
 */
@Slf4j
@Component
public class AiMcpTaskExecutionWorker implements DisposableBean {

  private final AiMcpTaskServiceImpl taskService;

  private final AiMcpPublicationRuntimeService publicationRuntimeService;

  private final AiMcpProperties properties;

  private final AiIdlePollBackoff idleBackoff;

  private final ExecutorService executor =
      Executors.newVirtualThreadPerTaskExecutor();

  private final ScheduledExecutorService heartbeatExecutor =
      Executors.newSingleThreadScheduledExecutor();

  private final AtomicBoolean accepting = new AtomicBoolean(true);

  private final AtomicInteger activeTasks = new AtomicInteger();

  private final String workerId = workerId();

  /**
   * Creates the MCP Task worker and its low-cardinality gauges.
   */
  public AiMcpTaskExecutionWorker(
      final AiMcpTaskServiceImpl taskService,
      final AiMcpPublicationRuntimeService publicationRuntimeService,
      final AiMcpProperties properties,
      final AiPollingProperties pollingProperties,
      final MeterRegistry meterRegistry
  ) {
    this.taskService = taskService;
    this.publicationRuntimeService = publicationRuntimeService;
    this.properties = properties;
    this.idleBackoff = new AiIdlePollBackoff(
        properties.getTaskWorkerPollInterval(),
        pollingProperties
    );
    Gauge.builder(
        "simplepoint.ai.mcp.tasks.active",
        activeTasks,
        AtomicInteger::doubleValue
    ).register(meterRegistry);
    Gauge.builder(
        "simplepoint.ai.mcp.tasks.accepting",
        accepting,
        value -> value.get() ? 1D : 0D
    ).register(meterRegistry);
  }

  /**
   * Claims available durable work without retaining a transaction.
   */
  @Scheduled(
      fixedDelayString =
          "${simplepoint.ai.mcp.task-worker-poll-interval:500ms}"
  )
  public void poll() {
    if (!properties.isTaskExecutionEnabled()
        || !accepting.get()
        || !idleBackoff.shouldPoll()) {
      return;
    }
    int capacity = maximumConcurrency() - activeTasks.get();
    if (capacity <= 0) {
      return;
    }
    try {
      List<ExecutionToken> tasks = taskService.claim(workerId, capacity);
      idleBackoff.recordResult(!tasks.isEmpty());
      tasks.forEach(this::submit);
    } catch (RuntimeException ex) {
      log.warn("Unable to poll MCP Tasks: {}", safeMessage(ex));
    }
  }

  @Override
  public void destroy() {
    accepting.set(false);
    executor.shutdown();
    heartbeatExecutor.shutdown();
    try {
      if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
      if (!heartbeatExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        heartbeatExecutor.shutdownNow();
      }
    } catch (InterruptedException ex) {
      executor.shutdownNow();
      heartbeatExecutor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  private void submit(final ExecutionToken token) {
    activeTasks.incrementAndGet();
    try {
      executor.execute(() -> execute(token));
    } catch (RejectedExecutionException ex) {
      activeTasks.decrementAndGet();
      log.warn("MCP Task {} was claimed while draining", token.taskId());
    }
  }

  private void execute(final ExecutionToken token) {
    ScheduledFuture<?> heartbeat = null;
    try {
      Duration interval = heartbeatInterval();
      heartbeat = heartbeatExecutor.scheduleWithFixedDelay(
          () -> taskService.renew(token),
          interval.toMillis(),
          interval.toMillis(),
          TimeUnit.MILLISECONDS
      );
      McpGatewayToolCallResult result =
          publicationRuntimeService.callPublishedTool(
              taskService.executionRequest(token)
          );
      taskService.complete(token, result);
    } catch (AiMcpTaskServiceImpl.StaleMcpTaskLeaseException ex) {
      log.debug("MCP Task {} was fenced", token.taskId());
    } catch (RuntimeException ex) {
      try {
        taskService.fail(token, ex);
      } catch (AiMcpTaskServiceImpl.StaleMcpTaskLeaseException ignored) {
        log.debug("MCP Task {} failure was fenced", token.taskId());
      }
    } finally {
      if (heartbeat != null) {
        heartbeat.cancel(false);
      }
      activeTasks.decrementAndGet();
    }
  }

  private int maximumConcurrency() {
    return Math.max(
        1,
        Math.min(properties.getTaskMaximumConcurrency(), 256)
    );
  }

  private Duration heartbeatInterval() {
    Duration lease = properties.getTaskLeaseDuration();
    if (lease == null || lease.isZero() || lease.isNegative()) {
      lease = Duration.ofMinutes(2);
    }
    Duration interval = lease.dividedBy(3);
    return interval.isZero() ? Duration.ofSeconds(1) : interval;
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
        ? "MCP Task worker failure" : message;
  }
}
