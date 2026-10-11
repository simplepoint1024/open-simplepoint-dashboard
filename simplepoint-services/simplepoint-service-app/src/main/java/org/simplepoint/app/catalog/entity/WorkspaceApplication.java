package org.simplepoint.app.catalog.entity;

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
@Table(name = "simpoint_app_definitions", indexes =
    @Index(name = "idx_simpoint_app_tenant_updated", columnList = "tenant_id,updated_at"))
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

  @Column(length = 500)
  private String description;

  @Column(length = 60)
  private String icon;

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
