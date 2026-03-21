package org.simplepoint.plugin.rbac.tenant.api.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;

/**
 * FeaturePermissionRelevance represents the relationship between features and permission authorities.
 * It extends BaseEntityImpl to inherit common entity properties such as ID, creation time, etc.
 * This entity is used to manage the associations between features and permission authorities in the system.
 */
@Data
@Entity
@Table(name = "simpoint_saas_feature_permission_rel")
@EqualsAndHashCode(callSuper = true)
public class FeaturePermissionRelevance extends BaseEntityImpl<String> {
  private String featureCode;
  private String permissionAuthority;
}
