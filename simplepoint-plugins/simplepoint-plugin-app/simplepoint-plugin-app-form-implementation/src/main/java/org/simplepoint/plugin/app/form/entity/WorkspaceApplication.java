package org.simplepoint.plugin.app.form.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Tenant-owned form/workflow application, distinct from a SaaS package application. */
@Entity
@Table(
    name = "simpoint_app_definitions",
    indexes = @Index(name = "idx_simpoint_app_tenant_updated", columnList = "tenant_id,updated_at"))
@Getter
@Setter
public class WorkspaceApplication {
  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "tenant_id", length = 64, nullable = false, updatable = false)
  private String tenantId;

  @Column(length = 120, nullable = false)
  private String name;

  @org.simplepoint.api.schema.LocalizedField(maxLength = 100)
  @jakarta.persistence.Convert(converter = org.simplepoint.core.convert.LocalizedTextConverter.class)
  @Column(name = "name_localization", columnDefinition = "TEXT")
  private org.simplepoint.api.locale.LocalizedText nameLocalization;

  @Column(name = "navigation_definition", columnDefinition = "TEXT")
  private String navigationDefinition;

  @Column(name = "navigation_pending", columnDefinition = "TEXT")
  private String navigationPending;

  @Column(name = "navigation_revision", nullable = false)
  private long navigationRevision;

  @Column(length = 500)
  private String description;

  @Column(length = 60)
  private String icon;

  /** Opt-in stable application directory for newly published managed pages. */
  @Column(name = "navigation_group", nullable = false, updatable = false)
  private boolean navigationGroup;

  @Column(length = 20, nullable = false)
  private String status;

  @Column(name = "main_page_id", length = 36, nullable = false)
  private String mainPageId;

  @Column(name = "created_by", length = 64)
  private String createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
