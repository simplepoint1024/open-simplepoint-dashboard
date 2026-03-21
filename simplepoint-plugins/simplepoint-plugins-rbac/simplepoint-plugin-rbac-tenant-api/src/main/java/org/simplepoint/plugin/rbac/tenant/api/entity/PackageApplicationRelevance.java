package org.simplepoint.plugin.rbac.tenant.api.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;

/**
 * PackageApplicationRelevance represents the relationship between packages and applications.
 * It extends BaseEntityImpl to inherit common entity properties such as ID, creation time, etc.
 * This entity is used to manage the associations between packages and applications in the system.
 */
@Data
@Entity
@Table(name = "simpoint_saas_package_application_rel")
@EqualsAndHashCode(callSuper = true)
public class PackageApplicationRelevance extends BaseEntityImpl<String> {
  private String packageCode;
  private String applicationCode;
}
