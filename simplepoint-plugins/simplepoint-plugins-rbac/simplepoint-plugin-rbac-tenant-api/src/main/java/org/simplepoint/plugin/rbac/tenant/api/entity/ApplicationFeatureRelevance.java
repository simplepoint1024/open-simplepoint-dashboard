package org.simplepoint.plugin.rbac.tenant.api.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;

/**
 * ApplicationFeatureRelevance represents the relationship between applications and features.
 * It extends BaseEntityImpl to inherit common entity properties such as ID, creation time, etc.
 * This entity is used to manage the associations between applications and features in the system.
 */
@Data
@Entity
@Table(name = "simpoint_saas_application_feature_rel")
@EqualsAndHashCode(callSuper = true)
public class ApplicationFeatureRelevance extends BaseEntityImpl<String> {
  private String applicationCode;
  private String featureCode;
}
