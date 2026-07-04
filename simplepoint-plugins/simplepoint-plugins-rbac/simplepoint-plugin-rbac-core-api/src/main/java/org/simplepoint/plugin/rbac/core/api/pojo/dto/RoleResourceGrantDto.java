package org.simplepoint.plugin.rbac.core.api.pojo.dto;

import java.io.Serializable;
import java.util.Set;
import lombok.Data;

/**
 * DTO for granting resources to a role.
 */
@Data
public class RoleResourceGrantDto implements Serializable {
  private String roleId;
  private Set<String> resourceCodes;
  private String dataScopeId;
  private String fieldScopeId;
}
