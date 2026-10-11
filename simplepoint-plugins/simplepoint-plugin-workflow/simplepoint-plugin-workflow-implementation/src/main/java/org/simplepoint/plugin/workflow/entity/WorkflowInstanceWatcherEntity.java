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

/** Tenant-qualified copied-matter membership for one process instance. */
@Entity
@Table(name = "simpoint_workflow_instance_watchers", indexes = {
    @Index(name = "idx_workflow_watcher_user_created",
        columnList = "tenant_id,user_id,created_at")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_workflow_watcher_instance_user",
        columnNames = {"process_instance_id", "user_id"})
})
@Getter
@Setter
public class WorkflowInstanceWatcherEntity {

  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "tenant_id", length = 64, nullable = false, updatable = false)
  private String tenantId;

  @Column(name = "process_instance_id", length = 64, nullable = false, updatable = false)
  private String processInstanceId;

  @Column(name = "user_id", length = 64, nullable = false, updatable = false)
  private String userId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;
}
