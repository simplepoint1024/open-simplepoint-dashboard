package org.simplepoint.plugin.ai.agent.service.execution;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.plugin.ai.agent.api.properties.AgentExecutionProperties;
import org.springframework.beans.factory.DisposableBean;
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

  private final ExecutorService executor =
      Executors.newVirtualThreadPerTaskExecutor();

  private final String workerId = workerId();

  /**
   * Creates the horizontally scalable Agent worker.
   */
  public AiAgentExecutionWorker(
      final AiAgentExecutionCoordinator coordinator,
      final AiAgentExecutionRunner runner,
      final AgentExecutionProperties properties
  ) {
    this.coordinator = coordinator;
    this.runner = runner;
    this.properties = properties;
  }

  /**
   * Claims work without retaining database transactions during model I/O.
   */
  @Scheduled(
      fixedDelayString =
          "${simplepoint.ai.agent.execution.poll-interval:500ms}"
  )
  public void poll() {
    if (!Boolean.TRUE.equals(properties.getEnabled())) {
      return;
    }
    try {
      coordinator.claim(workerId).forEach(task ->
          executor.execute(() -> runner.execute(task)));
    } catch (RuntimeException ex) {
      log.warn("Unable to poll Agent executions: {}", safeMessage(ex));
    }
  }

  @Override
  public void destroy() {
    executor.close();
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
        ? "Agent execution failed" : message;
  }
}
