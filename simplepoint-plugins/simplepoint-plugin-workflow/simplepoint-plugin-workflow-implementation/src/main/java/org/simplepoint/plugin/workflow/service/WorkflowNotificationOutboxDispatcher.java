package org.simplepoint.plugin.workflow.service;

import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.simplepoint.plugin.notification.api.model.NotificationPriority;
import org.simplepoint.plugin.notification.api.service.NotificationDeliveryService;
import org.simplepoint.plugin.notification.api.vo.NotificationDeliveryCommand;
import org.simplepoint.plugin.workflow.entity.WorkflowNotificationOutboxEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Retries workflow message-center delivery independently of process transactions. */
@Component
public class WorkflowNotificationOutboxDispatcher {

  private static final int MAX_ERROR_LENGTH = 1000;

  private final EntityManager entityManager;
  private final NotificationDeliveryService deliveryService;

  /** Creates the outbox dispatcher. */
  public WorkflowNotificationOutboxDispatcher(
      final EntityManager entityManager,
      final NotificationDeliveryService deliveryService
  ) {
    this.entityManager = entityManager;
    this.deliveryService = deliveryService;
  }

  /** Delivers a bounded retry batch; recipient-side idempotency makes replica races harmless. */
  @Scheduled(fixedDelayString = "${simplepoint.workflow.notification-outbox-delay:5s}")
  @Transactional
  public void dispatch() {
    Instant now = Instant.now();
    List<WorkflowNotificationOutboxEntity> batch = entityManager.createQuery(
            "select o from WorkflowNotificationOutboxEntity o"
                + " where o.status <> 'SENT' and o.nextAttemptAt <= :now"
                + " order by o.createdAt asc",
            WorkflowNotificationOutboxEntity.class
        ).setParameter("now", now)
        .setMaxResults(50)
        .getResultList();
    batch.forEach(this::deliver);
  }

  private void deliver(final WorkflowNotificationOutboxEntity item) {
    try {
      deliveryService.deliver(new NotificationDeliveryCommand(
          "workflow", item.getEventKey(), item.getTargetUserId(), item.getTitle(),
          item.getContent(), item.getLinkUrl(), NotificationPriority.NORMAL, null
      ));
      item.setStatus("SENT");
      item.setSentAt(Instant.now());
      item.setLastError(null);
    } catch (RuntimeException exception) {
      int attempts = item.getAttempts() + 1;
      item.setAttempts(attempts);
      item.setStatus("RETRY");
      item.setNextAttemptAt(Instant.now().plus(backoff(attempts)));
      String message = exception.getMessage() == null
          ? exception.getClass().getSimpleName() : exception.getMessage();
      item.setLastError(message.substring(0, Math.min(message.length(), MAX_ERROR_LENGTH)));
    }
  }

  private static Duration backoff(final int attempts) {
    long seconds = Math.min(300L, 1L << Math.min(attempts, 8));
    return Duration.ofSeconds(seconds);
  }
}
