package org.simplepoint.plugin.ai.skill.service.execution;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.plugin.ai.core.service.schedule.AiIdlePollBackoff;
import org.simplepoint.plugin.ai.core.service.schedule.AiPollingProperties;
import org.simplepoint.plugin.ai.skill.api.properties.SkillExecutionProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls durable Skill executions and runs slow MCP calls on virtual threads.
 */
@Slf4j
@Component
@ConditionalOnProperty(
    prefix = SkillExecutionProperties.PREFIX,
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class AiSkillExecutionWorker implements DisposableBean {

  private final AiSkillExecutionCoordinator coordinator;

  private final AiSkillWorkflowExecutor workflowExecutor;

  private final SkillExecutionProperties properties;

  private final AiIdlePollBackoff idleBackoff;

  private final ExecutorService executor =
      Executors.newVirtualThreadPerTaskExecutor();

  private final String workerId = workerId();

  /**
   * Creates the horizontally scalable Skill execution worker.
   */
  public AiSkillExecutionWorker(
      final AiSkillExecutionCoordinator coordinator,
      final AiSkillWorkflowExecutor workflowExecutor,
      final SkillExecutionProperties properties,
      final AiPollingProperties pollingProperties
  ) {
    this.coordinator = coordinator;
    this.workflowExecutor = workflowExecutor;
    this.properties = properties;
    this.idleBackoff = new AiIdlePollBackoff(
        properties.getPollInterval(),
        pollingProperties
    );
  }

  /**
   * Claims durable work without holding a transaction during MCP I/O.
   */
  @Scheduled(
      fixedDelayString =
          "${simplepoint.ai.skill.execution.poll-interval:500ms}"
  )
  public void poll() {
    if (!Boolean.TRUE.equals(properties.getEnabled())
        || !idleBackoff.shouldPoll()) {
      return;
    }
    try {
      List<AiSkillExecutionCoordinator.ExecutionTask> tasks =
          coordinator.claim(workerId);
      idleBackoff.recordResult(!tasks.isEmpty());
      tasks.forEach(task ->
          executor.execute(() -> execute(task)));
    } catch (RuntimeException ex) {
      log.warn("Unable to poll Skill executions: {}", safeMessage(ex));
    }
  }

  @Override
  public void destroy() {
    executor.close();
  }

  private void execute(
      final AiSkillExecutionCoordinator.ExecutionTask task
  ) {
    try {
      workflowExecutor.execute(task);
    } catch (RuntimeException ex) {
      log.warn(
          "Skill execution {} worker stopped: {}",
          task.executionId(),
          safeMessage(ex)
      );
    }
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
        ? "Skill execution failed" : message;
  }
}
