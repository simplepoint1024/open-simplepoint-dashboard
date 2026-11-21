package org.simplepoint.plugin.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The durable serialization point for a client-assigned batch ID. */
@Entity
@Table(name = "simpoint_workflow_history_removal_batches")
public class WorkflowHistoryRemovalBatchEntity {
  @Id
  @Column(name = "batch_key", length = 64)
  private String batchKey;
}
