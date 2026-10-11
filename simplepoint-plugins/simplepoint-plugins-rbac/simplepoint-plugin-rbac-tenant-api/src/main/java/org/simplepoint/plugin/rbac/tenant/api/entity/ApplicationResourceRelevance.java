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
@Table(name = "simpoint_saas_application_resource_rel", indexes = @jakarta.persistence.Index(
    name = "idx_resource_application_license", columnList = "resourceCode,applicationCode,deletedAt"))
public class ApplicationResourceRelevance extends BaseEntityImpl<String> {
  private String resourceCode;
  private String applicationCode;
}
