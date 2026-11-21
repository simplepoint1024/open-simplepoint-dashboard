package org.simplepoint.plugin.app.form.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Tracks permanent form cleanup independently of the object being removed. */
@Entity
@Table(name = "simpoint_app_form_removals")
@Getter
@Setter
public class ApplicationFormRemovalEntity {
  @Id @Column(length = 36)
  private String id;
  @Column(name = "tenant_id", length = 64, nullable = false)
  private String tenantId;
  @Column(name = "application_id", length = 36, nullable = false)
  private String applicationId;
  @Column(name = "form_id", length = 36, nullable = false, unique = true)
  private String formId;
  @Column(name = "form_version", nullable = false)
  private int formVersion;
  @Column(name = "draft_revision")
  private Long draftRevision;
  @Column(name = "requested_at", nullable = false)
  private Instant requestedAt;
  @Column(name = "completed_at")
  private Instant completedAt;
}
