package org.simplepoint.plugin.rbac.core.api.pojo.dto;

import java.util.Set;
import lombok.Data;

/**
 * UserRoleRelevanceDto is a data transfer object that encapsulates
 * the relationship between a user role and its associated permissions.
 */
@Data
public class RolePermissionsRelevanceDto {
  private String roleId;
  private Set<String> permissionAuthority;

  /**
   * Optional data scope ID to apply to all permissions in this batch.
   * When set, all resulting RolePermissionsRelevance records will reference this DataScope.
   */
  private String dataScopeId;

  /**
   * Optional field scope ID to apply to all permissions in this batch.
   * When set, all resulting RolePermissionsRelevance records will reference this FieldScope.
   */
  private String fieldScopeId;
}
