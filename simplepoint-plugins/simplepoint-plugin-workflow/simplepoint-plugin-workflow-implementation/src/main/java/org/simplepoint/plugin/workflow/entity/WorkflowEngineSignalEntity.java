package org.simplepoint.plugin.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Transactional wake-up queue, not business audit history; consumed rows can be removed. */
@Entity
@Table(
    name = "simpoint_workflow_engine_signals",
    indexes = {
      @Index(name = "idx_workflow_signal_ready", columnList = "next_attempt_at,created_at")
    })
@Getter
@Setter
public class WorkflowEngineSignalEntity {
  @Id
  @Column(length = 36)
  private String id;

  // No FK: start events fire before the platform instance is inserted in the same transaction.
  @Column(name = "process_instance_id", length = 64, nullable = false, updatable = false)
  private String processInstanceId;

  @Column(name = "event_type", length = 80, nullable = false, updatable = false)
  private String eventType;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  @Column(nullable = false)
  private int attempts;

  @Column(name = "last_error", length = 1000)
  private String lastError;
}
