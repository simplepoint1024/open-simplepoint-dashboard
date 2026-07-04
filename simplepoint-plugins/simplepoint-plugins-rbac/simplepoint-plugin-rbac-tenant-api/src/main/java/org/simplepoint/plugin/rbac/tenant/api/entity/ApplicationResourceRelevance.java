package org.simplepoint.plugin.rbac.tenant.api.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;

/**
 * Application-resource relation.
 */
@Data
@Entity
@EqualsAndHashCode(callSuper = true)
@Table(name = "simpoint_saas_application_resource_rel")
public class ApplicationResourceRelevance extends BaseEntityImpl<String> {
  private String resourceCode;
  private String applicationCode;
}
