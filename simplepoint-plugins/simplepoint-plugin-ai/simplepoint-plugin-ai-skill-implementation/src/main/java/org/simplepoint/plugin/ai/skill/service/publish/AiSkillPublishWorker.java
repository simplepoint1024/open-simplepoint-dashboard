package org.simplepoint.plugin.ai.skill.service.publish;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.plugin.ai.skill.api.properties.SkillPublishProperties;
import org.simplepoint.plugin.ai.skill.service.publish.AiSkillPublishCoordinator.PublishWork;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls durable publication tasks and runs Registry I/O on virtual threads. */
@Slf4j
@Component
@ConditionalOnProperty(
    prefix = SkillPublishProperties.PREFIX,
    name = "enabled",
    havingValue = "true"
)
public class AiSkillPublishWorker implements DisposableBean {

  private final AiSkillPublishCoordinator coordinator;

  private final AiSkillPublishProcessor processor;

  private final ExecutorService executor =
      Executors.newVirtualThreadPerTaskExecutor();

  private final AtomicBoolean accepting = new AtomicBoolean(true);

  private final AtomicInteger activeTasks = new AtomicInteger();

  private final AiSkillPublishMetrics metrics;

  private final String workerId = workerId();

  /** Creates the managed publication worker. */
  public AiSkillPublishWorker(
      final AiSkillPublishCoordinator coordinator,
      final AiSkillPublishProcessor processor,
      final AiSkillPublishMetrics metrics,
      final MeterRegistry meterRegistry
  ) {
    this.coordinator = coordinator;
    this.processor = processor;
    this.metrics = metrics;
    Gauge.builder(
        "simplepoint.ai.skill.publish.worker.active",
        activeTasks,
        AtomicInteger::doubleValue
    ).register(meterRegistry);
    Gauge.builder(
        "simplepoint.ai.skill.publish.worker.accepting",
        accepting,
        value -> value.get() ? 1D : 0D
    ).register(meterRegistry);
  }

  /** Claims tasks without retaining transactions during network I/O. */
  @Scheduled(
      fixedDelayString =
          "${simplepoint.ai.skill.publish.poll-interval:1s}"
  )
  public void poll() {
    if (!accepting.get()) {
      return;
    }
    try {
      List<PublishWork> tasks = coordinator.claim(workerId);
      tasks.forEach(this::submit);
    } catch (RuntimeException ex) {
      metrics.outcome("poll_failed");
      log.warn("Unable to poll Skill publication tasks: {}", safeMessage(ex));
    }
  }

  private void submit(final PublishWork task) {
    activeTasks.incrementAndGet();
    try {
      executor.execute(() -> {
        try {
          processor.execute(task);
        } finally {
          activeTasks.decrementAndGet();
        }
      });
    } catch (RejectedExecutionException ex) {
      activeTasks.decrementAndGet();
      metrics.outcome("submission_rejected");
      log.warn(
          "Unable to submit Skill publication task {}; worker is stopping",
          task.taskId()
      );
    }
  }

  @Override
  public void destroy() {
    accepting.set(false);
    executor.close();
  }

  private static String workerId() {
    String hostname = System.getenv("HOSTNAME");
    String node = hostname == null || hostname.isBlank()
        ? "local" : hostname.trim();
    return "skill-publish-" + node + "-"
        + UUID.randomUUID().toString().substring(0, 8);
  }

  private static String safeMessage(final RuntimeException exception) {
    String message = exception == null ? null : exception.getMessage();
    return message == null || message.isBlank()
        ? "Skill publication polling failed" : message;
  }
}
