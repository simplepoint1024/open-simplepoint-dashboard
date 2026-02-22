package org.simplepoint.plugin.rbac.menu.api.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;

/**
 * Represents the relationship between menus and permissions in the
 * RBAC (Role-Based Access Control) system.
 * This entity is mapped to the `auth_role_permissions_rel` table
 * and defines the association between menu authorities and permission authorities.
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Entity
@Table(name = "auth_permissions_menu_rel")
@Schema(title = "角色权限关联实体", description = "表示RBAC系统中角色与权限之间的关联关系")
public class MenuPermissionsRelevance extends TenantBaseEntityImpl<String> {

  private String menuId;

  private String permissionAuthority;

  /**
   * Constructs a new MenuPermissionsRelevance with the specified menu and permission authorities.
   *
   * @param menuId              the authority string of the menu
   * @param permissionAuthority the authority string of the permission
   */
  public MenuPermissionsRelevance(String menuId, String permissionAuthority) {
    this.menuId = menuId;
    this.permissionAuthority = permissionAuthority;
  }

  /**
   * Default constructor for MenuPermissionsRelevance.
   */
  public MenuPermissionsRelevance() {
  }
}
