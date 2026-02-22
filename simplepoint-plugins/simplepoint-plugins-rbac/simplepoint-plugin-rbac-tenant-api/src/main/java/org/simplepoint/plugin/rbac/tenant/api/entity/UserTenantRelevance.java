package org.simplepoint.plugin.rbac.tenant.api.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;

/**
 * UserTenantRelevance represents the relationship between users and tenants.
 * It extends BaseEntityImpl to inherit common entity properties such as ID, creation time, etc.
 * This entity is used to manage the associations between users and tenants in the system.
 */
@Data
@Entity
@Table(name = "auth_user_tenant_rel")
@EqualsAndHashCode(callSuper = true)
public class UserTenantRelevance extends BaseEntityImpl<String> {
  private String userId;
  private String tenantId;
}
