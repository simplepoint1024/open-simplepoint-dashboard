package org.simplepoint.plugin.rbac.menu.api.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Data;
import org.simplepoint.plugin.rbac.menu.api.entity.id.MenuPermissionsRelevanceId;

/**
 * Represents the relationship between menus and permissions in the
 * RBAC (Role-Based Access Control) system.
 * This entity is mapped to the `security_role_permissions_rel` table
 * and defines the association between menu authorities and permission authorities.
 */
@Data
@Entity
@IdClass(MenuPermissionsRelevanceId.class)
@Table(name = "security_permissions_menu_rel")
@Schema(title = "角色权限关联实体", description = "表示RBAC系统中角色与权限之间的关联关系")
public class MenuPermissionsRelevance {

  @Id
  private String menuAuthority;

  @Id
  private String permissionAuthority;

  /**
   * Constructs a new MenuPermissionsRelevance with the specified menu and permission authorities.
   *
   * @param menuAuthority       the authority string of the menu
   * @param permissionAuthority the authority string of the permission
   */
  public MenuPermissionsRelevance(String menuAuthority, String permissionAuthority) {
    this.menuAuthority = menuAuthority;
    this.permissionAuthority = permissionAuthority;
  }

  /**
   * Default constructor for MenuPermissionsRelevance.
   */
  public MenuPermissionsRelevance() {}
}
