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
  private Set<String> permissionAuthority;

  /**
   * Constructs a new {@code MenuPermissionsRelevanceDto} with the specified menu ID and permission IDs.
   *
   * @param menuId        the ID of the menu
   * @param permissionAuthority the set of associated permission IDs
   */
  public MenuPermissionsRelevanceDto(String menuId, Set<String> permissionAuthority) {
    this.menuId = menuId;
    this.permissionAuthority = permissionAuthority;
  }

  /**
   * Default constructor.
   */
  public MenuPermissionsRelevanceDto() {
  }
}
