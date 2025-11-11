package org.simplepoint.plugin.rbac.core.api.pojo.vo;

import java.io.Serializable;
import lombok.Data;

/**
 * Data Transfer Object (DTO) for selecting roles.
 * 角色选择的数据传输对象
 */
@Data
public class UserRoleRelevanceVo implements Serializable {
  private String name;
  private String description;
  private String authority;

  /**
   * Constructs a new RoleSelectDto with the specified name, authority, and description.
   *
   * @param name        the name of the role
   * @param authority   the authority string of the role
   * @param description the description of the role
   */
  public UserRoleRelevanceVo(
      String name,
      String authority,
      String description
  ) {
    this.name = name;
    this.authority = authority;
    this.description = description;
  }
}
