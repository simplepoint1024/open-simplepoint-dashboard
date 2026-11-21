package org.simplepoint.plugin.rbac.tenant.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Purchase-content labels captured at assignment; unrelated to platform resource synchronization versions. */
@Data
@Entity
@Table(name = "simpoint_saas_tenant_package_instance_resources")
@IdClass(TenantPackageResourceKey.class)
@NoArgsConstructor
public class TenantPackageResourceDefinition {
  @Id
  @Column(name = "instance_id", length = 36, nullable = false, updatable = false)
  private String instanceId;

  @Id
  @Column(name = "resource_id", length = 36, nullable = false, updatable = false)
  private String resourceId;

  @Column(name = "resource_code", length = 120, updatable = false)
  private String resourceCode;

  @Column(name = "resource_name", length = 100, updatable = false)
  private String resourceName;

  /** True for an action explicitly added after the original activation. */
  @Column(nullable = false, updatable = false)
  private boolean supplemental;

  /** Captures labels; the owning instance binds both immutable identity components. */
  public TenantPackageResourceDefinition(String resourceCode, String resourceName) {
    this.resourceCode = resourceCode;
    this.resourceName = resourceName;
  }
}
