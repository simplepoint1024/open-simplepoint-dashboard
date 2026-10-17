package org.simplepoint.plugin.app.form.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Durable accepted intent and immutable business snapshot, advanced through short leased transactions. */
@Entity
@Table(name = "simpoint_app_form_submissions", indexes = {
    @Index(name = "idx_app_submission_recovery", columnList = "step,next_attempt_at,lease_until"),
    @Index(name = "idx_app_submission_page", columnList = "tenant_id,application_id,page_id,created_at"),
    @Index(name = "idx_app_submission_process", columnList = "tenant_id,process_instance_id"),
    @Index(name = "idx_app_submission_record", columnList = "tenant_id,form_id,record_id,created_at")
})
@Getter
@Setter
public class ApplicationFormSubmissionEntity {
  @Id
  @Column(name = "id", nullable = false, length = 64)
  private String id;

  @Column(name = "tenant_id", nullable = false, length = 64, updatable = false)
  private String tenantId;

  @Column(name = "application_id", nullable = false, length = 36, updatable = false)
  private String applicationId;

  @Column(name = "page_id", nullable = false, length = 36, updatable = false)
  private String pageId;

  @Column(name = "form_id", nullable = false, length = 36, updatable = false)
  private String formId;

  @Column(name = "form_version", nullable = false, updatable = false)
  private int formVersion;

  @Column(name = "actor_id", nullable = false, length = 64, updatable = false)
  private String actorId;

  @Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
  private String requestFingerprint;

  @Column(name = "intent_json", nullable = false, columnDefinition = "TEXT", updatable = false)
  private String intentJson;

  @Column(name = "title", length = 240, nullable = false, updatable = false)
  private String title;

  @Column(name = "definition_id", nullable = false, length = 64, updatable = false)
  private String definitionId;

  @Column(name = "definition_version", nullable = false, updatable = false)
  private int definitionVersion;

  @Column(name = "previous_submission_id", nullable = true, length = 64, updatable = false)
  private String previousSubmissionId;

  @Column(name = "record_id", nullable = true, length = 64)
  private String recordId;

  @Column(name = "record_version", nullable = true)
  private Long recordVersion;

  @Column(name = "snapshot_json", nullable = true, columnDefinition = "TEXT")
  private String snapshotJson;

  @Column(name = "process_instance_id", nullable = true, length = 64)
  private String processInstanceId;

  @Column(name = "process_status", length = 24)
  private String processStatus;

  @Column(name = "status", nullable = false, length = 24)
  private String status;

  @Column(name = "step", nullable = false, length = 24)
  private String step;

  @Column(name = "error_code", nullable = true, length = 80)
  private String errorCode;

  @Column(name = "attempt", nullable = false)
  private int attempt;

  @Column(name = "execution_token", nullable = true, length = 36)
  private String executionToken;

  @Column(name = "lease_until", nullable = true)
  private Instant leaseUntil;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;
}
