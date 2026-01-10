package org.simplepoint.security.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.api.security.base.BaseRolePermissionsRelevance;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;

/**
 * Represents the relationship between resources and permissions in the
 * RBAC (Role-Based Access Control) system.
 * This entity is mapped to the `auth_resources_relevance` table
 * and defines the association between resource authorities and permission authorities.
 */
@Data
@Entity
@Table(name = "auth_resources_rel")
@EqualsAndHashCode(callSuper = true)
public class ResourcesPermissionsRelevance extends BaseEntityImpl<String> implements BaseRolePermissionsRelevance {

  /**
   * The authority of the permission associated with the relationship.
   * This field specifies the unique identifier or scope of the permission.
   */
  @Column(nullable = false)
  private String permissionId;

  /**
   * The authority of the resource associated with the relationship.
   * This field specifies the unique identifier or scope of the resource.
   */
  @Column(nullable = false)
  private String resourceId;

  /**
   * Default constructor.
   */
  public ResourcesPermissionsRelevance() {
  }

  /**
   * Parameterized constructor to create a ResourcesPermissionsRelevance
   * with specified resource and permission authorities.
   *
   * @param permissionId the authority of the resource
   * @param resourceId   the authority of the permission
   */
  public ResourcesPermissionsRelevance(String permissionId, String resourceId) {
    this.permissionId = permissionId;
    this.resourceId = resourceId;
  }
}
