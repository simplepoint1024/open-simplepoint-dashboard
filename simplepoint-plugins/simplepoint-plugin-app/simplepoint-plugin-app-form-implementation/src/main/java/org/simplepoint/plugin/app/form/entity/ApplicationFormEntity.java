package org.simplepoint.plugin.app.form.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormStatus;

/** Stable tenant-owned form identity. */
@Entity
@Table(
    name = "simpoint_app_forms",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_simpoint_app_form_code",
        columnNames = {"tenant_id", "application_id", "code"}
    ),
    indexes = {
        @Index(
            name = "idx_simpoint_app_form_app",
            columnList = "tenant_id,application_id,created_at"
        ),
        @Index(
            name = "idx_simpoint_app_form_status",
            columnList = "tenant_id,status,updated_at"
        )
    }
)
@Getter
@Setter
public class ApplicationFormEntity {

  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "tenant_id", length = 64, nullable = false, updatable = false)
  private String tenantId;

  @Column(name = "application_id", length = 36, nullable = false, updatable = false)
  private String applicationId;

  @Column(length = 64, nullable = false, updatable = false)
  private String code;

  @Column(length = 120, nullable = false)
  private String name;

  @Column(length = 500)
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(length = 20, nullable = false)
  private ApplicationFormStatus status;

  @Column(name = "draft_revision", nullable = false)
  private long draftRevision;

  @Column(name = "latest_version", nullable = false)
  private int latestVersion;

  @Column(name = "published_version")
  private Integer publishedVersion;

  @Column(name = "write_policy_version", nullable = false)
  private long writePolicyVersion;

  @Column(name = "physical_model_id", length = 64)
  private String physicalModelId;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  @Column(name = "created_by", length = 64)
  private String createdBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
