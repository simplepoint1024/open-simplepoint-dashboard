package org.simplepoint.plugin.app.form.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Application-owned removal intent spanning form storage and authorization catalog cleanup. */
@Entity
@Table(name = "simpoint_app_catalog_removals")
@Getter
@Setter
public class WorkspaceApplicationRemovalEntity {
  @Id @Column(length = 36)
  private String id;
  @Column(name = "tenant_id", length = 64, nullable = false)
  private String tenantId;
  @Column(name = "application_id", length = 36, nullable = false, unique = true)
  private String applicationId;
  @Column(name = "application_updated_at", nullable = false)
  private Instant applicationUpdatedAt;
  @Column(name = "navigation_revision", nullable = false)
  private long navigationRevision;
  @Column(name = "requested_at", nullable = false)
  private Instant requestedAt;
  @Column(name = "catalog_requested_at")
  private Instant catalogRequestedAt;
  @Column(name = "completed_at")
  private Instant completedAt;
}
