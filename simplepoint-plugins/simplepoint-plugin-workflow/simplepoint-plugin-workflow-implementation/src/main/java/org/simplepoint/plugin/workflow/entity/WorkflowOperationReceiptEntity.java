package org.simplepoint.plugin.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Durable confirmation committed with the Flowable operation, never with submitted form values. */
@Entity
@Table(name = "simpoint_workflow_operation_receipts", indexes = {
    @Index(name = "idx_workflow_receipt_tenant_created", columnList = "tenant_id,created_at")
})
@Getter
@Setter
public class WorkflowOperationReceiptEntity {
  @Id
  @Column(length = 64)
  private String id;
  @Column(name = "tenant_id", length = 64, nullable = false, updatable = false)
  private String tenantId;
  @Column(name = "actor_id", length = 64, nullable = false, updatable = false)
  private String actorId;
  @Column(name = "request_fingerprint", length = 64, nullable = false, updatable = false)
  private String requestFingerprint;
  @Column(name = "result_json", columnDefinition = "TEXT", nullable = false, updatable = false)
  private String resultJson;
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;
}
