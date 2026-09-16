package org.simplepoint.plugin.ai.runtime.service.scheduler;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.plugin.ai.core.service.schedule.AiIdlePollBackoff;
import org.simplepoint.plugin.ai.core.service.schedule.AiPollingProperties;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeWorkloadObservation;
import org.simplepoint.plugin.ai.runtime.api.properties.AiRuntimeProperties;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeNodeOperations;
import org.simplepoint.plugin.ai.runtime.service.scheduler.AiRuntimeWorkloadCoordinator.DispatchTask;
import org.simplepoint.plugin.ai.runtime.service.scheduler.AiRuntimeWorkloadCoordinator.ObservationTask;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls durable assignments and performs slow node I/O outside transactions.
 */
@Slf4j
@Component
@ConditionalOnProperty(
    prefix = AiRuntimeProperties.PREFIX,
    name = "scheduling-enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class AiRuntimeWorkloadScheduler implements DisposableBean {

  private final AiRuntimeWorkloadCoordinator coordinator;

  private final AiRuntimeNodeOperations nodeOperations;

  private final AiIdlePollBackoff idleBackoff;

  private final ExecutorService executor =
      Executors.newVirtualThreadPerTaskExecutor();

  /**
   * Creates the asynchronous workload scheduler.
   */
  public AiRuntimeWorkloadScheduler(
      final AiRuntimeWorkloadCoordinator coordinator,
      final AiRuntimeNodeOperations nodeOperations,
      final AiRuntimeProperties properties,
      final AiPollingProperties pollingProperties
  ) {
    this.coordinator = coordinator;
    this.nodeOperations = nodeOperations;
    this.idleBackoff = new AiIdlePollBackoff(
        properties.getWorkloadSchedulerInterval(),
        pollingProperties
    );
  }

  /**
   * Claims durable work across AI replicas and submits bounded node calls.
   */
  @Scheduled(
      fixedDelayString =
          "${simplepoint.ai.runtime.workload-scheduler-interval:1s}"
  )
  public void poll() {
    if (!idleBackoff.shouldPoll()) {
      return;
    }
    try {
      int expired = coordinator.expireLeases();
      int assigned = coordinator.assignPending();
      List<DispatchTask> dispatches = coordinator.claimDispatches();
      List<ObservationTask> observations = coordinator.claimObservations();
      idleBackoff.recordResult(
          expired > 0
              || assigned > 0
              || !dispatches.isEmpty()
              || !observations.isEmpty()
      );
      dispatches.forEach(task ->
          executor.execute(() -> dispatch(task)));
      observations.forEach(task ->
          executor.execute(() -> observe(task)));
    } catch (RuntimeException ex) {
      log.warn("Unable to poll OCI Runtime workloads: {}", ex.getMessage());
    }
  }

  private void dispatch(final DispatchTask task) {
    try {
      RuntimeWorkloadObservation observation = nodeOperations.start(
          task.advertiseUrl(),
          task.request()
      );
      coordinator.confirmStarted(task, observation);
    } catch (RuntimeException ex) {
      coordinator.recordFailure(
          task.workloadId(),
          task.leaseId(),
          task.fencingToken(),
          false,
          ex
      );
      log.warn(
          "OCI Runtime workload {} dispatch failed: {}",
          task.workloadId(),
          safeMessage(ex)
      );
    }
  }

  private void observe(final ObservationTask task) {
    try {
      RuntimeWorkloadObservation observation = task.stop()
          ? nodeOperations.stop(
              task.advertiseUrl(),
              task.workloadId(),
              task.leaseId(),
              task.fencingToken()
          )
          : nodeOperations.status(
              task.advertiseUrl(),
              task.workloadId(),
              task.leaseId(),
              task.fencingToken()
          );
      if (terminal(observation) || task.stop()) {
        deleteBestEffort(task);
      }
      coordinator.confirmObserved(task, observation);
    } catch (RuntimeException ex) {
      coordinator.recordFailure(
          task.workloadId(),
          task.leaseId(),
          task.fencingToken(),
          task.stop(),
          ex
      );
      log.warn(
          "OCI Runtime workload {} observation failed: {}",
          task.workloadId(),
          safeMessage(ex)
      );
    }
  }

  private void deleteBestEffort(final ObservationTask task) {
    try {
      nodeOperations.delete(
          task.advertiseUrl(),
          task.workloadId(),
          task.leaseId(),
          task.fencingToken()
      );
    } catch (RuntimeException ex) {
      log.warn(
          "Unable to remove stopped OCI Runtime workload {}: {}",
          task.workloadId(),
          safeMessage(ex)
      );
    }
  }

  private boolean terminal(final RuntimeWorkloadObservation observation) {
    String state = observation == null || observation.state() == null
        ? "" : observation.state().trim().toLowerCase(Locale.ROOT);
    return List.of("exited", "dead", "removing").contains(state);
  }

  private String safeMessage(final RuntimeException error) {
    String message = error.getMessage();
    return message == null || message.isBlank()
        ? error.getClass().getSimpleName() : message;
  }

  /**
   * Stops accepting node I/O when the AI control plane shuts down.
   */
  @Override
  public void destroy() {
    executor.shutdownNow();
  }
}
