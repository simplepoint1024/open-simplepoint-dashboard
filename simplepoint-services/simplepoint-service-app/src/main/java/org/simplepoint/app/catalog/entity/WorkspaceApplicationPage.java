package org.simplepoint.app.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Page metadata; a published page may later bind to a tenant resource. */
@Entity
@Table(name = "simpoint_app_pages", indexes =
    @Index(name = "idx_simpoint_app_page_tenant_app", columnList = "tenant_id,application_id,sort_order"))
@Getter
@Setter
public class WorkspaceApplicationPage {
  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "tenant_id", length = 64, nullable = false, updatable = false)
  private String tenantId;

  @Column(name = "application_id", length = 36, nullable = false, updatable = false)
  private String applicationId;

  @Column(length = 12, nullable = false)
  private String kind;

  @Column(length = 120, nullable = false)
  private String name;

  @Column(length = 500)
  private String description;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(length = 20, nullable = false)
  private String status;

  @Column(name = "resource_id", length = 64)
  private String resourceId;

  @Column(name = "resource_code", length = 160)
  private String resourceCode;

  @Column(name = "form_id", length = 36)
  private String formId;

  @Column(name = "form_version")
  private Integer formVersion;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
