package org.simplepoint.plugin.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;
import lombok.Getter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;

/** Unified read-only task/action projection. Never serialize it: it includes scope-only IDs. */
@Entity
@Immutable
@Subselect(WorkflowReadSql.INBOX)
@Synchronize({"simpoint_workflow_instances", "simpoint_workflow_actions"})
@Getter
public class WorkflowInboxReadModel {
  @Id private String id;

  @Column(name = "tenant_id")
  private String tenantId;

  @Column(name = "task_id")
  private String taskId;

  @Column(name = "process_instance_id")
  private String processInstanceId;

  @Column(name = "row_type")
  private String rowType;

  private String title;

  @Column(name = "definition_name")
  private String definitionName;

  @Column(name = "task_name")
  private String taskName;

  @Column(name = "starter_name")
  private String starterName;

  @Column(name = "assignee_name")
  private String assigneeName;

  @Column(name = "actor_name")
  private String actorName;

  @Column(name = "assignee_id")
  private String assigneeId;

  @Column(name = "actor_id")
  private String actorId;

  @Column(name = "decision_actor_id")
  private String decisionActorId;

  private String outcome;

  @Column(name = "instance_status")
  private String instanceStatus;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "due_at")
  private Instant dueAt;

  @Column(name = "handled_at")
  private Instant handledAt;

  private Integer priority;
  private String comment;
  private boolean active;
}
