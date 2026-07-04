package org.simplepoint.plugin.rbac.core.api.pojo.vo;

import java.io.Serializable;
import lombok.Data;

/**
 * Role overview row for the access center role list.
 */
@Data
public class AccessCenterRoleOverviewVo implements Serializable {

  private RoleRelevanceVo role;

  private long resourceCount;

  private long assignedUserCount;

  private AccessCenterScopeVo dataScope;

  private AccessCenterScopeVo fieldScope;

  /**
   * Creates an empty role overview.
   */
  public AccessCenterRoleOverviewVo() {
  }

  /**
   * Creates a role overview.
   *
   * @param role role summary
   * @param resourceCount authorized resource count
   * @param assignedUserCount assigned user count
   * @param dataScope data scope summary
   * @param fieldScope field scope summary
   */
  public AccessCenterRoleOverviewVo(
      RoleRelevanceVo role,
      long resourceCount,
      long assignedUserCount,
      AccessCenterScopeVo dataScope,
      AccessCenterScopeVo fieldScope
  ) {
    this.role = role;
    this.resourceCount = resourceCount;
    this.assignedUserCount = assignedUserCount;
    this.dataScope = dataScope;
    this.fieldScope = fieldScope;
  }
}
