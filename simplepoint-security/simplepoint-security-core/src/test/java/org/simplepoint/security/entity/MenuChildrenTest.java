package org.simplepoint.security.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.security.MenuChildren;

class MenuChildrenTest {

  @Test
  void toMenu_copiesBasicFields() {
    MenuChildren mc = new MenuChildren();
    mc.setLabel("Settings");
    mc.setPath("/settings");
    mc.setType("page");
    mc.setSort(5);

    Menu menu = mc.toMenu();
    assertThat(menu.getLabel()).isEqualTo("Settings");
    assertThat(menu.getPath()).isEqualTo("/settings");
    assertThat(menu.getType()).isEqualTo("page");
    assertThat(menu.getSort()).isEqualTo(5);
  }

  @Test
  void toMenu_returnsMenuInstance() {
    MenuChildren mc = new MenuChildren();
    Menu result = mc.toMenu();
    assertThat(result).isInstanceOf(Menu.class);
    assertThat(result).isNotInstanceOf(MenuChildren.class);
  }

  @Test
  void menuChildren_childrenField() {
    MenuChildren child = new MenuChildren();
    child.setLabel("Child");

    MenuChildren parent = new MenuChildren();
    parent.setChildren(Set.of(child));

    assertThat(parent.getChildren()).containsExactly(child);
  }

  @Test
  void menuChildren_featureCodes() {
    MenuChildren mc = new MenuChildren();
    mc.setFeatureCodes(Set.of("feature.read", "feature.write"));
    assertThat(mc.getFeatureCodes()).containsExactlyInAnyOrder("feature.read", "feature.write");
  }

  @Test
  void menuChildren_permissions() {
    MenuChildren mc = new MenuChildren();
    Permissions p = new Permissions();
    p.setResource("user.list");
    mc.setPermissions(Set.of(p));
    assertThat(mc.getPermissions()).containsExactly(p);
  }
}
