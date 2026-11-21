package org.simplepoint.plugin.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/** A participant's private archive marker for one list entry. */
@Entity
@Table(name = "simpoint_workflow_personal_archive", uniqueConstraints = @UniqueConstraint(
    name = "uk_workflow_personal_archive_entry",
    columnNames = {"tenant_id", "user_id", "list_name", "entry_id"}))
public class WorkflowPersonalArchiveEntity {
  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "tenant_id", length = 64, nullable = false)
  private String tenantId;

  @Column(name = "user_id", length = 64, nullable = false)
  private String userId;

  @Column(name = "list_name", length = 16, nullable = false)
  private String listName;

  @Column(name = "entry_id", length = 128, nullable = false)
  private String entryId;

  @Column(name = "process_instance_id", length = 64, nullable = false)
  private String processInstanceId;

  @Column(name = "archived_at", nullable = false)
  private Instant archivedAt;
}
