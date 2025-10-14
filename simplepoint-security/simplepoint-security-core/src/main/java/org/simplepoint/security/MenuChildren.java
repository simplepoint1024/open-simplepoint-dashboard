package org.simplepoint.security;

import java.util.Set;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.security.entity.Menu;

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
}
