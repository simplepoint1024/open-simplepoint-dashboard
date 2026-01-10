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
  private Set<String> permissionIds;
}
