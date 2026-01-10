package org.simplepoint.security;

import java.util.Set;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.security.entity.Menu;
import org.simplepoint.security.entity.Permissions;
import org.springframework.beans.BeanUtils;

/**
 * Represents a menu entity that is initialized with a hierarchical structure.
 * This class extends the Menu class and includes a reference to its child menu.
 * It is used for initializing menus in the RBAC (Role-Based Access Control) system.
 *
 * @since 1.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MenuChildren extends Menu {
  private Set<MenuChildren> children;
  private Set<Permissions> permissions;

  /**
   * Converts this MenuChildren instance to a Menu instance.
   *
   * @return the Menu instance
   */
  public Menu toMenu() {
    Menu menu = new Menu();
    BeanUtils.copyProperties(this, menu);
    return menu;
  }
}
