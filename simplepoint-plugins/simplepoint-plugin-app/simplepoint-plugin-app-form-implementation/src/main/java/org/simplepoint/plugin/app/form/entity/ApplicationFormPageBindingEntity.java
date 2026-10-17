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
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPageBindingStatus;

/** Durable publication of one immutable form version to one APP page. */
@Entity
@Table(
    name = "simpoint_app_form_page_bindings",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_simpoint_app_form_page_binding_key",
            columnNames = {"tenant_id", "idempotency_key"}
        ),
        @UniqueConstraint(
            name = "uk_app_page_binding_generation",
            columnNames = {"tenant_id", "page_id", "generation"}
        )
    },
    indexes = {
        @Index(
            name = "idx_simpoint_app_form_page_binding_page",
            columnList = "tenant_id,page_id,status,updated_at"
        ),
        @Index(
            name = "idx_simpoint_app_form_page_binding_form",
            columnList = "tenant_id,form_id,form_version"
        )
    }
)
@Getter
@Setter
public class ApplicationFormPageBindingEntity {

  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "tenant_id", length = 64, nullable = false, updatable = false)
  private String tenantId;

  @Column(name = "application_id", length = 36, nullable = false, updatable = false)
  private String applicationId;

  @Column(name = "page_id", length = 36, nullable = false, updatable = false)
  private String pageId;

  @Column(name = "form_id", length = 36, nullable = false, updatable = false)
  private String formId;

  @Column(name = "form_version", nullable = false, updatable = false)
  private int formVersion;

  @Column(name = "scene_id", length = 80, nullable = false, updatable = false)
  private String sceneId;

  @Column(name = "idempotency_key", length = 80, nullable = false, updatable = false)
  private String idempotencyKey;

  @Column(nullable = false, updatable = false)
  private long generation;

  @Column(name = "policy_protocol_version", nullable = false, updatable = false)
  private int policyProtocolVersion;

  @Enumerated(EnumType.STRING)
  @Column(length = 24, nullable = false)
  private ApplicationFormPageBindingStatus status;

  @Column(name = "resource_managed_key", length = 160, nullable = false, updatable = false)
  private String resourceManagedKey;

  @Column(name = "resource_id", length = 64)
  private String resourceId;

  @Column(name = "resource_code", length = 160)
  private String resourceCode;

  @Column(name = "runtime_path", length = 200)
  private String runtimePath;

  @Column(name = "error_code", length = 80)
  private String errorCode;

  @Column(nullable = false)
  private int attempt;

  @Column(name = "execution_token", length = 36)
  private String executionToken;

  @Column(name = "lease_until")
  private Instant leaseUntil;

  @Column(name = "activated_at")
  private Instant activatedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
