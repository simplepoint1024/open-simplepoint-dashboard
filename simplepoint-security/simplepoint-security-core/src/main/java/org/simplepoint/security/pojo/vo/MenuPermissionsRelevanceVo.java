package org.simplepoint.security.pojo.vo;

import lombok.Data;

/**
 * MenuPermissionsRelevanceVo is a value object that encapsulates
 * the details of permissions associated with a menu.
 */
@Data
public class MenuPermissionsRelevanceVo {
  private String name;
  private String authority;
  private String description;

  /**
   * Constructs a new MenuPermissionsRelevanceVo with the specified permission details.
   *
   * @param name        the name of the permission
   * @param authority   the authority string of the permission
   * @param description the description of the permission
   */
  public MenuPermissionsRelevanceVo(String name, String authority, String description) {
    this.name = name;
    this.authority = authority;
    this.description = description;
  }
}
