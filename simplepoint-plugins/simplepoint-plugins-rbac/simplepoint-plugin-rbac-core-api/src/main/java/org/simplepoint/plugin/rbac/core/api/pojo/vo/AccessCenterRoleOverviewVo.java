package org.simplepoint.plugin.rbac.core.api.pojo.vo;

import java.io.Serializable;
import lombok.Data;

/**
 * Role overview row for the access center role list.
 */
@Data
public class AccessCenterRoleOverviewVo implements Serializable {

  private RoleRelevanceVo role;

  private long permissionCount;

  private long assignedUserCount;

  private AccessCenterScopeVo dataScope;

  private AccessCenterScopeVo fieldScope;

  public AccessCenterRoleOverviewVo() {
  }

  public AccessCenterRoleOverviewVo(
      RoleRelevanceVo role,
      long permissionCount,
      long assignedUserCount,
      AccessCenterScopeVo dataScope,
      AccessCenterScopeVo fieldScope
  ) {
    this.role = role;
    this.permissionCount = permissionCount;
    this.assignedUserCount = assignedUserCount;
    this.dataScope = dataScope;
    this.fieldScope = fieldScope;
  }
}
