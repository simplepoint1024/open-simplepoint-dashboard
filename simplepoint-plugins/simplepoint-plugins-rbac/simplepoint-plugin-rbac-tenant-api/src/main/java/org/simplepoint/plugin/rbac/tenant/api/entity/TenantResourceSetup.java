package org.simplepoint.plugin.rbac.tenant.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Data;

/** Tenant directory preparation state; not a resource revision or synchronization version. */
@Data
@Entity
@Table(name = "simpoint_saas_tenant_resource_setup")
public class TenantResourceSetup {
  /** New bootstrap tenants await declarations; existing tenants require explicit historical adoption. */
  public enum State { INITIALIZATION_REQUIRED, ADOPTION_REQUIRED, READY }

  @Id
  @Column(name = "tenant_id", length = 36, nullable = false, updatable = false)
  private String tenantId;

  @Enumerated(EnumType.STRING)
  @Column(length = 32, nullable = false)
  private State state;

  @Column(name = "initialized_at")
  private Instant initializedAt;

  @Column(name = "adopted_by", length = 36)
  private String adoptedBy;

  @Column(name = "adopted_at")
  private Instant adoptedAt;
}
