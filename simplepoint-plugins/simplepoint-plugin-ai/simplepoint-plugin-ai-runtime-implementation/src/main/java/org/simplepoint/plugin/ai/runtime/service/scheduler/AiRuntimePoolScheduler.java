package org.simplepoint.plugin.ai.runtime.service.scheduler;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.plugin.ai.core.service.schedule.AiIdlePollBackoff;
import org.simplepoint.plugin.ai.core.service.schedule.AiPollingProperties;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeImageObservation;
import org.simplepoint.plugin.ai.runtime.api.properties.AiRuntimeProperties;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeNodeOperations;
import org.simplepoint.plugin.ai.runtime.service.scheduler.AiRuntimePoolCoordinator.PrewarmTask;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reconciles runtime pools and performs slow OCI pulls outside transactions.
 */
@Slf4j
@Component
@ConditionalOnProperty(
    prefix = AiRuntimeProperties.PREFIX,
    name = "scheduling-enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class AiRuntimePoolScheduler implements DisposableBean {

  private final AiRuntimePoolCoordinator coordinator;

  private final AiRuntimeNodeOperations nodeOperations;

  private final AiIdlePollBackoff idleBackoff;

  private final ExecutorService executor =
      Executors.newVirtualThreadPerTaskExecutor();

  /**
   * Creates the asynchronous pool scheduler.
   */
  public AiRuntimePoolScheduler(
      final AiRuntimePoolCoordinator coordinator,
      final AiRuntimeNodeOperations nodeOperations,
      final AiRuntimeProperties properties,
      final AiPollingProperties pollingProperties
  ) {
    this.coordinator = coordinator;
    this.nodeOperations = nodeOperations;
    this.idleBackoff = new AiIdlePollBackoff(
        properties.getPoolSchedulerInterval(),
        pollingProperties
    );
  }

  /**
   * Claims image prewarms and converges pool desired state.
   */
  @Scheduled(
      fixedDelayString = "${simplepoint.ai.runtime.pool-scheduler-interval:2s}"
  )
  public void poll() {
    if (!idleBackoff.shouldPoll()) {
      return;
    }
    try {
      List<PrewarmTask> prewarms = coordinator.claimPrewarms();
      int reconciled = coordinator.reconcile();
      idleBackoff.recordResult(!prewarms.isEmpty() || reconciled > 0);
      prewarms.forEach(task ->
          executor.execute(() -> prewarm(task)));
    } catch (RuntimeException ex) {
      log.warn("Unable to reconcile OCI Runtime pools: {}", safeMessage(ex));
    }
  }

  private void prewarm(final PrewarmTask task) {
    try {
      RuntimeImageObservation observation = nodeOperations.prepare(
          task.advertiseUrl(),
          task.image()
      );
      coordinator.confirmPrewarm(task, observation);
    } catch (RuntimeException ex) {
      coordinator.recordPrewarmFailure(task, ex);
      log.warn(
          "OCI Runtime pool {} prewarm on node {} failed: {}",
          task.poolId(),
          task.nodeId(),
          safeMessage(ex)
      );
    }
  }

  private String safeMessage(final RuntimeException error) {
    String message = error.getMessage();
    return message == null || message.isBlank()
        ? error.getClass().getSimpleName() : message;
  }

  @Override
  public void destroy() {
    executor.shutdownNow();
  }
}
