package org.simplepoint.plugin.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Durable, idempotent bridge from workflow transactions to the message center. */
@Entity
@Table(name = "simpoint_workflow_notification_outbox", indexes = {
    @Index(name = "idx_workflow_outbox_delivery", columnList = "status,next_attempt_at")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_workflow_outbox_event", columnNames = "event_key")
})
@Getter
@Setter
public class WorkflowNotificationOutboxEntity {

  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "tenant_id", length = 64, nullable = false, updatable = false)
  private String tenantId;

  @Column(name = "event_key", length = 200, nullable = false, updatable = false)
  private String eventKey;

  @Column(name = "target_user_id", length = 64, nullable = false, updatable = false)
  private String targetUserId;

  @Column(length = 200, nullable = false, updatable = false)
  private String title;

  @Column(length = 2000, nullable = false, updatable = false)
  private String content;

  @Column(name = "link_url", length = 1024, nullable = false, updatable = false)
  private String linkUrl;

  @Column(length = 16, nullable = false)
  private String status;

  @Column(nullable = false)
  private int attempts;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "sent_at")
  private Instant sentAt;

  @Column(name = "last_error", length = 1000)
  private String lastError;
}
