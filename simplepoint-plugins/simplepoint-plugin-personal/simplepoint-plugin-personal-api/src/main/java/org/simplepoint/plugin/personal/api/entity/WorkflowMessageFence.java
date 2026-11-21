package org.simplepoint.plugin.personal.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Durable serialization point for individual deliveries and historical workflow removal. */
@Entity
@Table(name = "simpoint_personal_workflow_message_fences")
@Getter
@Setter
public class WorkflowMessageFence {
  @Id @Column(length = 36)
  private String id;
  @Column(nullable = false)
  private boolean removed;
}
