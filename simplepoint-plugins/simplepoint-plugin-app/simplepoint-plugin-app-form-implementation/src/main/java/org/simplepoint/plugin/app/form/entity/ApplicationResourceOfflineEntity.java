package org.simplepoint.plugin.app.form.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Durable lifecycle operation; mandatory participant completion is independent of MQ receipt. */
@Entity
@Table(name = "simpoint_app_resource_offline", uniqueConstraints = @UniqueConstraint(
    name = "uk_app_resource_offline_generation", columnNames = {"tenant_id", "page_id", "generation"}),
    indexes = @Index(name = "ix_app_resource_offline_page", columnList = "tenant_id,page_id,state"))
@Getter
@Setter
public class ApplicationResourceOfflineEntity {
  @Id
  @Column(length = 36)
  private String id;
  @Column(name = "tenant_id", length = 64, nullable = false)
  private String tenantId;
  @Column(name = "application_id", length = 36, nullable = false)
  private String applicationId;
  @Column(name = "page_id", length = 36, nullable = false)
  private String pageId;
  @Column(name = "form_id", length = 36, nullable = false)
  private String formId;
  @Column(name = "binding_id", length = 36, nullable = false)
  private String bindingId;
  @Column(name = "resource_id", length = 64, nullable = false)
  private String resourceId;
  @Column(nullable = false)
  private long generation;
  @Column(length = 16, nullable = false)
  private String state;
  @Column(name = "request_event_id", length = 36, nullable = false)
  private String requestEventId;
  @Column(name = "required_participants", length = 160, nullable = false)
  private String requiredParticipants;
  @Column(name = "completed_participants", length = 160, nullable = false)
  private String completedParticipants;
  @Column(name = "retry_request_id", length = 36)
  private String retryRequestId;
  @Column(name = "retry_actor", length = 64)
  private String retryActor;
  @Column(name = "retry_at")
  private Instant retryAt;
  @Column(nullable = false)
  private int retries;
  @Column(name = "created_by", length = 64, nullable = false)
  private String createdBy;
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
