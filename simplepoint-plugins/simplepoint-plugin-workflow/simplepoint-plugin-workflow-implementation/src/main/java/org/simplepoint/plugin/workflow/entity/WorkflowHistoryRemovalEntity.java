package org.simplepoint.plugin.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Fences exact retries and tracks owner acknowledgements without retaining form or task contents. */
@Entity
@Table(name = "simpoint_workflow_history_removals")
@Getter
@Setter
public class WorkflowHistoryRemovalEntity {
  @Id
  @Column(name = "instance_id", length = 64)
  private String instanceId;
  @Column(name = "tenant_id", length = 64, nullable = false, updatable = false)
  private String tenantId;
  @Column(name = "actor_id", length = 64, nullable = false, updatable = false)
  private String actorId;
  @Column(name = "operation_id", length = 36, nullable = false, updatable = false)
  private String operationId;
  @Column(name = "batch_id", length = 36, nullable = false, updatable = false)
  private String batchId;
  @Column(name = "definition_version", nullable = false, updatable = false)
  private int definitionVersion;
  @Column(name = "ended_at", nullable = false, updatable = false)
  private Instant endedAt;
  @Column(name = "requested_at", nullable = false, updatable = false)
  private Instant requestedAt;
  @Column(name = "app_completed", nullable = false)
  private boolean appCompleted;
  @Column(name = "personal_completed", nullable = false)
  private boolean personalCompleted;
  @Column(name = "completed_at")
  private Instant completedAt;
}
