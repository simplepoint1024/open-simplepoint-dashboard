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

  /**
   * Constructs a new RolePermissionsRelevanceVo with the specified permission details.
   *
   * @param id          the unique identifier of the permission
   * @param name        the name of the permission
   * @param authority   the authority string of the permission
   * @param description the description of the permission
   */
  public PermissionsRelevanceVo(String id, String name, String authority, String description) {
    this.id = id;
    this.name = name;
    this.authority = authority;
    this.description = description;
  }

  /**
   * Default constructor.
   */
  public PermissionsRelevanceVo() {
  }
}
