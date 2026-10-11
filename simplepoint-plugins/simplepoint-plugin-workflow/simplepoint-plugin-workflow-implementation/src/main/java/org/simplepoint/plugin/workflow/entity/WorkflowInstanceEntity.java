package org.simplepoint.plugin.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Platform projection binding a Flowable instance to its immutable business version. */
@Entity
@Table(
    name = "simpoint_workflow_instances",
    indexes = {
      @Index(name = "idx_workflow_instance_tenant_started", columnList = "tenant_id,started_at"),
      @Index(
          name = "idx_workflow_instance_starter_started",
          columnList = "tenant_id,starter_id,started_at"),
      @Index(name = "idx_workflow_instance_business_key", columnList = "tenant_id,business_key")
    })
@Getter
@Setter
public class WorkflowInstanceEntity {

  @Id
  @Column(name = "process_instance_id", length = 64)
  private String processInstanceId;

  @Column(name = "tenant_id", length = 64, nullable = false, updatable = false)
  private String tenantId;

  @Column(name = "definition_id", length = 36, nullable = false, updatable = false)
  private String definitionId;

  @Column(name = "definition_version_id", length = 36, nullable = false, updatable = false)
  private String definitionVersionId;

  @Column(name = "previous_instance_id", length = 64, unique = true, updatable = false)
  private String previousInstanceId;

  @Column(name = "definition_key", length = 80, nullable = false, updatable = false)
  private String definitionKey;

  @Column(name = "definition_name", length = 160, nullable = false, updatable = false)
  private String definitionName;

  @Column(name = "definition_version", nullable = false, updatable = false)
  private int definitionVersion;

  @Column(name = "business_key", length = 160)
  private String businessKey;

  @Column(length = 240, nullable = false)
  private String title;

  @Column(name = "starter_id", length = 64, nullable = false, updatable = false)
  private String starterId;

  @Column(length = 20, nullable = false)
  private String status;

  @Column(name = "started_at", nullable = false, updatable = false)
  private Instant startedAt;

  @Column(name = "ended_at")
  private Instant endedAt;

  @Column(name = "cancel_reason", length = 500)
  private String cancelReason;
}
