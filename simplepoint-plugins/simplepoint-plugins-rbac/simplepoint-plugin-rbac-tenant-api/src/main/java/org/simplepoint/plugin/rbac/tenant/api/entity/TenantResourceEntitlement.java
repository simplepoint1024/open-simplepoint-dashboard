package org.simplepoint.plugin.rbac.tenant.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Data;

/** Current resource time balance, written atomically with the package credit ledger. */
@Data
@Entity
@IdClass(TenantResourceKey.class)
@Table(name = "simpoint_saas_tenant_resource_entitlements")
public class TenantResourceEntitlement {
  @Id
  @Column(name = "tenant_id", length = 36, nullable = false, updatable = false)
  private String tenantId;

  /** Matches the tenant catalog resource ID. */
  @Id
  @Column(name = "resource_id", length = 36, nullable = false, updatable = false)
  private String id;

  @Column(name = "starts_at", nullable = false)
  private Instant startsAt;

  /** Null is explicitly unlimited; absence of the row means no package entitlement. */
  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
