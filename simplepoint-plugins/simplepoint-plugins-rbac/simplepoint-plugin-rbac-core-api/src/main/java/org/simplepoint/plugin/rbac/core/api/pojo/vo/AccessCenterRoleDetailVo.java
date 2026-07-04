package org.simplepoint.plugin.rbac.core.api.pojo.vo;

import java.io.Serializable;
import java.util.Collection;
import java.util.LinkedHashSet;
import lombok.Data;

/**
 * Aggregated role authorization detail for the access center.
 */
@Data
public class AccessCenterRoleDetailVo implements Serializable {

  private RoleRelevanceVo role;

  private Collection<String> authorizedPermissions = new LinkedHashSet<>();

  private RoleScopeAssignmentVo scopeAssignment;

  private AccessCenterScopeVo dataScope;

  private AccessCenterScopeVo fieldScope;

  private long assignedUserCount;

  private Collection<AccessCenterUserImpactVo> assignedUsers;
}
