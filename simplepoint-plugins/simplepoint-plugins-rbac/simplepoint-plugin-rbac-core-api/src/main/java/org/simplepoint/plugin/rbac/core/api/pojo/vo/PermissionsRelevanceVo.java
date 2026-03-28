package org.simplepoint.plugin.rbac.core.api.pojo.vo;

import lombok.Data;

/**
 * RolePermissionsRelevanceVo is a value object that encapsulates
 * the details of permissions associated with a role.
 */
@Data
public class PermissionsRelevanceVo {
  private String id;
  private String name;
  private String authority;
  private String description;
  private Integer type;

  /**
   * Constructs a new RolePermissionsRelevanceVo with the specified permission details.
   *
   * @param id          the unique identifier of the permission
   * @param name        the name of the permission
   * @param authority   the authority string of the permission
   * @param description the description of the permission
   * @param type        0 for access permissions, 1 for operation permissions
   */
  public PermissionsRelevanceVo(String id, String name, String authority, String description, Integer type) {
    this.id = id;
    this.name = name;
    this.authority = authority;
    this.description = description;
    this.type = type;
  }

  /**
   * Default constructor.
   */
  public PermissionsRelevanceVo() {
  }
}
