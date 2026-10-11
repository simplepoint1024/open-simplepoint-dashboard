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

/** Immutable business action, committed with the corresponding engine transition. */
@Entity
@Table(
    name = "simpoint_workflow_actions",
    indexes = {
      @Index(
          name = "idx_workflow_action_actor_time",
          columnList = "tenant_id,actor_id,occurred_at"),
      @Index(
          name = "idx_workflow_action_instance_time",
          columnList = "tenant_id,process_instance_id,occurred_at")
    },
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_workflow_action_task_decision",
          columnNames = "decision_task_id"),
      @UniqueConstraint(
          name = "uk_workflow_action_instance_terminal",
          columnNames = "terminal_instance_id")
    })
@Getter
@Setter
public class WorkflowActionEntity {

  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "tenant_id", length = 64, nullable = false, updatable = false)
  private String tenantId;

  @Column(name = "process_instance_id", length = 64, nullable = false, updatable = false)
  private String processInstanceId;

  @Column(name = "task_id", length = 64, updatable = false)
  private String taskId;

  @Column(name = "task_definition_key", length = 255, updatable = false)
  private String taskDefinitionKey;

  // Only a terminal task decision occupies this unique key; claims and process actions do not.
  @Column(name = "decision_task_id", length = 64, updatable = false)
  private String decisionTaskId;

  // At most one explicit process termination; approvals/claims never occupy this key.
  @Column(name = "terminal_instance_id", length = 64, updatable = false)
  private String terminalInstanceId;

  @Column(name = "action_type", length = 20, nullable = false, updatable = false)
  private String actionType;

  @Column(name = "actor_id", length = 64, nullable = false, updatable = false)
  private String actorId;

  @Column(length = 2000, updatable = false)
  private String comment;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;
}
