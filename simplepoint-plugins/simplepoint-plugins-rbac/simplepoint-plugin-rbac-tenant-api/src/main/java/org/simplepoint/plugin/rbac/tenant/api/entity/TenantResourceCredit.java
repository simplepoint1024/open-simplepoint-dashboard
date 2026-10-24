package org.simplepoint.plugin.rbac.tenant.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Data;

/** Immutable activation receipt for one distinct resource in one package instance. */
@Data
@Entity
@Table(name = "simpoint_saas_tenant_resource_credits", uniqueConstraints = @UniqueConstraint(
    name = "uq_tenant_resource_credit", columnNames = {"tenant_id", "instance_id", "resource_id"}))
public class TenantResourceCredit {
  @Id
  @Column(length = 36, nullable = false, updatable = false)
  private String id;

  @Column(name = "tenant_id", length = 36, nullable = false, updatable = false)
  private String tenantId;

  @Column(name = "instance_id", length = 36, nullable = false, updatable = false)
  private String instanceId;

  @Column(name = "resource_id", length = 36, nullable = false, updatable = false)
  private String resourceId;

  @Column(name = "duration_days", updatable = false)
  private Integer durationDays;

  @Column(name = "activated_at", nullable = false, updatable = false)
  private Instant activatedAt;

  @Column(name = "activated_by", length = 36, nullable = false, updatable = false)
  private String activatedBy;

  /** Result at activation time, retained even when later purchases extend the live balance. */
  @Column(name = "result_expires_at", updatable = false)
  private Instant resultExpiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;
}
