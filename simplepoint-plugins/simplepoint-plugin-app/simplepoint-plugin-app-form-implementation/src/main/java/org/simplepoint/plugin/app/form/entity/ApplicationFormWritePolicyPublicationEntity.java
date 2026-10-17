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

/** Immutable target intent and monotonic acknowledgement for one page publication. */
@Entity
@Table(name = "simpoint_app_form_policy_publications", uniqueConstraints = {
    @UniqueConstraint(name = "uk_app_policy_binding", columnNames = {"tenant_id", "binding_id"}),
    @UniqueConstraint(name = "uk_app_policy_version", columnNames = {"tenant_id", "form_id", "target_version"})
}, indexes = @Index(name = "idx_app_policy_pending", columnList = "tenant_id,form_id,status"))
@Getter
@Setter
public class ApplicationFormWritePolicyPublicationEntity {

  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "tenant_id", length = 64, nullable = false, updatable = false)
  private String tenantId;

  @Column(name = "application_id", length = 36, nullable = false, updatable = false)
  private String applicationId;

  @Column(name = "form_id", length = 36, nullable = false, updatable = false)
  private String formId;

  @Column(name = "page_id", length = 36, nullable = false, updatable = false)
  private String pageId;

  @Column(name = "binding_id", length = 36, nullable = false, updatable = false)
  private String bindingId;

  @Column(name = "governing_version", nullable = false, updatable = false)
  private int governingVersion;

  @Column(name = "expected_version", nullable = false, updatable = false)
  private long expectedVersion;

  @Column(name = "target_version", nullable = false, updatable = false)
  private long targetVersion;

  @Column(name = "command_json", columnDefinition = "TEXT", nullable = false, updatable = false)
  private String commandJson;

  @Enumerated(EnumType.STRING)
  @Column(length = 24, nullable = false)
  private FormWritePolicyPublicationStatus status;

  @Column(name = "target_fingerprint", length = 64)
  private String targetFingerprint;

  @Column(name = "inspection_json", columnDefinition = "TEXT")
  private String inspectionJson;

  @Column(name = "cancellation_json", columnDefinition = "TEXT")
  private String cancellationJson;

  @Column(name = "created_by", length = 64, updatable = false)
  private String createdBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;
}
