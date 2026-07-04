package org.simplepoint.plugin.rbac.core.api.pojo.dto;

import java.io.Serializable;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Data;

/**
 * DTO used by the access center to replace a role's resource and scope assignment.
 */
@Data
public class AccessCenterRoleAuthorizationDto implements Serializable {

  private String roleId;

  private Set<String> resourceCodes = new LinkedHashSet<>();

  private String dataScopeId;

  private String fieldScopeId;
}
