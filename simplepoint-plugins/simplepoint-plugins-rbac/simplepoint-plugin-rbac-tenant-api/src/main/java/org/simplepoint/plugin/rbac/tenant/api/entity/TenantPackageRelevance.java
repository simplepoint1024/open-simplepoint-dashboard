package org.simplepoint.plugin.rbac.tenant.api.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;

/**
 * TenantPackageRelevance represents the relationship between tenants and packages.
 * It extends BaseEntityImpl to inherit common entity properties such as ID, creation time, etc.
 * This entity is used to manage the associations between tenants and packages in the system.
 */
@Data
@Entity
@Table(name = "simpoint_saas_tenant_package_rel")
@EqualsAndHashCode(callSuper = true)
public class TenantPackageRelevance extends BaseEntityImpl<String> {
  private String tenantId;
  private String packageCode;
}
