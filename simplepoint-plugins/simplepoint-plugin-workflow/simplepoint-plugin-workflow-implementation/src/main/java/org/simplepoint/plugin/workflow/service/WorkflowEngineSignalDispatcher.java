package org.simplepoint.plugin.workflow.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import org.simplepoint.plugin.workflow.entity.WorkflowEngineSignalEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Reconciles committed engine events in isolated, bounded and retryable transactions. */
@Component
public class WorkflowEngineSignalDispatcher {
  private final EntityManager entityManager;
  private final WorkflowCenterServiceImpl workflow;
  private final TransactionTemplate transaction;

  /** Creates an isolated-transaction consumer for committed signals. */
  public WorkflowEngineSignalDispatcher(
      EntityManager entityManager,
      WorkflowCenterServiceImpl workflow,
      PlatformTransactionManager manager) {
    this.entityManager = entityManager;
    this.workflow = workflow;
    transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** Processes a bounded batch and retains failures for backed-off retry. */
  @Scheduled(fixedDelayString = "${simplepoint.workflow.engine-signal-delay:2s}")
  public void dispatch() {
    List<String> ids =
        transaction.execute(
            status ->
                entityManager
                    .createQuery(
                        "select s.id from WorkflowEngineSignalEntity s where s.nextAttemptAt <="
                            + " :now order by s.createdAt, s.id",
                        String.class)
                    .setParameter("now", Instant.now())
                    .setMaxResults(50)
                    .getResultList());
    if (ids == null) {
      return;
    }
    for (String id : ids) {
      try {
        transaction.executeWithoutResult(
            status -> {
              WorkflowEngineSignalEntity signal = lock(id);
              if (signal == null || signal.getNextAttemptAt().isAfter(Instant.now())) {
                return;
              }
              workflow.synchronizeEngineState(signal.getProcessInstanceId());
              entityManager.remove(signal);
            });
      } catch (RuntimeException error) {
        // Never catch a failed database command and continue in its rollback-only transaction.
        transaction.executeWithoutResult(
            status -> {
              WorkflowEngineSignalEntity signal = lock(id);
              if (signal == null) {
                return;
              }
              int attempts = signal.getAttempts() + 1;
              signal.setAttempts(attempts);
              signal.setNextAttemptAt(
                  Instant.now().plusSeconds(Math.min(300, 1L << Math.min(8, attempts))));
              String message =
                  error.getMessage() == null
                      ? error.getClass().getSimpleName()
                      : error.getMessage();
              signal.setLastError(message.substring(0, Math.min(1000, message.length())));
            });
      }
    }
  }

  private WorkflowEngineSignalEntity lock(String id) {
    return entityManager.find(WorkflowEngineSignalEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
  }
}
