package org.simplepoint.security.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Data;
import org.simplepoint.api.security.base.BaseRolePermissionsRelevance;
import org.simplepoint.security.entity.id.ResourcesPermissionsRelevanceId;

/**
 * Represents the relationship between resources and permissions in the
 * RBAC (Role-Based Access Control) system.
 * This entity is mapped to the `auth_resources_relevance` table
 * and defines the association between resource authorities and permission authorities.
 */
@Data
@Entity
@IdClass(ResourcesPermissionsRelevanceId.class)
@Table(name = "auth_resources_rel")
public class ResourcesPermissionsRelevance implements BaseRolePermissionsRelevance {

  /**
   * The authority of the permission associated with the relationship.
   * This field specifies the unique identifier or scope of the permission.
   */
  @Id
  @Column(nullable = false)
  private String permissionAuthority;

  /**
   * The authority of the resource associated with the relationship.
   * This field specifies the unique identifier or scope of the resource.
   */
  @Id
  @Column(nullable = false)
  private String resourceAuthority;

  /**
   * Default constructor.
   */
  public ResourcesPermissionsRelevance() {
  }

  /**
   * Parameterized constructor to create a ResourcesPermissionsRelevance
   * with specified resource and permission authorities.
   *
   * @param resourceAuthority   the authority of the resource
   * @param permissionAuthority the authority of the permission
   */
  public ResourcesPermissionsRelevance(String resourceAuthority, String permissionAuthority) {
    this.resourceAuthority = resourceAuthority;
    this.permissionAuthority = permissionAuthority;
  }
}
