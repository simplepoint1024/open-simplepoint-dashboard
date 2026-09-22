package org.simplepoint.plugin.rbac.tenant.api.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.api.schema.DictionaryField;
import org.simplepoint.plugin.rbac.tenant.api.constants.TenantDictionaryCodes;

/**
 * TenantUserRelevance represents the relationship between users and tenants.
 * It extends BaseEntityImpl to inherit common entity properties such as ID, creation time, etc.
 * This entity is used to manage the associations between users and tenants in the system.
 */
@Data
@Entity
@Table(name = "simpoint_saas_tenant_user_rel", uniqueConstraints = @jakarta.persistence.UniqueConstraint(
    name = "uk_tenant_member_user", columnNames = {"tenant_id", "user_id"}))
@EqualsAndHashCode(callSuper = true)
public class TenantUserRelevance extends BaseEntityImpl<String> {
  private String userId;
  private String tenantId;
  private String orgId;
  @DictionaryField(TenantDictionaryCodes.TENANT_MEMBER_USER_TYPE)
  @jakarta.persistence.Column(name = "user_type", length = 32, nullable = false)
  private String userType = "INTERNAL";
  private Boolean enabled = true;
  @jakarta.persistence.Version
  private Long revision;

  @Override
  public void prePersist() {
    if (userType == null || userType.isBlank()) userType = "INTERNAL";
    super.prePersist();
  }
}
