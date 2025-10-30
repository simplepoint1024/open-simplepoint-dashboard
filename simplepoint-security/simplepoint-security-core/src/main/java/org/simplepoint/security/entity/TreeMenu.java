package org.simplepoint.security.entity;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Represents a tree structure of menus, extending the base Menu entity.
 *
 * <p>This class includes a list of child menus, allowing for hierarchical
 * representation of menu items.</p>
 *
 * @author JinxuLiu
 * @since 1.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TreeMenu extends Menu {
  private List<Menu> children = new ArrayList<>();
}
