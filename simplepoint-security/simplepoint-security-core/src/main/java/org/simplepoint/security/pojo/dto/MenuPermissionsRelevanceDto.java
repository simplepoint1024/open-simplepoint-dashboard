package org.simplepoint.security.pojo.dto;

import java.util.Set;
import lombok.Data;

/**
 * MenuPermissionsRelevanceDto is a data transfer object that encapsulates
 * the relationship between a menu and its associated permissions.
 */
@Data
public class MenuPermissionsRelevanceDto {
  private String menuId;
  private Set<String> permissionIds;

  /**
   * Constructs a new {@code MenuPermissionsRelevanceDto} with the specified menu ID and permission IDs.
   *
   * @param menuId        the ID of the menu
   * @param permissionIds the set of associated permission IDs
   */
  public MenuPermissionsRelevanceDto(String menuId, Set<String> permissionIds) {
    this.menuId = menuId;
    this.permissionIds = permissionIds;
  }

  /**
   * Default constructor.
   */
  public MenuPermissionsRelevanceDto() {
  }
}
