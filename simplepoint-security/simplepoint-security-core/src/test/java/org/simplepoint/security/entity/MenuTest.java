package org.simplepoint.security.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MenuTest {

  @Test
  void prePersist_setsAuthorityFromPath_whenAuthorityIsNull() {
    Menu menu = new Menu();
    menu.setPath("/admin/dashboard");
    menu.prePersist();
    assertThat(menu.getAuthority()).isEqualTo(".admin.dashboard");
    assertThat(menu.getDisabled()).isFalse();
  }

  @Test
  void prePersist_setsAuthorityFromPath_whenAuthorityIsEmpty() {
    Menu menu = new Menu();
    menu.setAuthority("");
    menu.setPath("/user/list");
    menu.prePersist();
    assertThat(menu.getAuthority()).isEqualTo(".user.list");
  }

  @Test
  void prePersist_doesNotOverrideAuthority_whenAlreadySet() {
    Menu menu = new Menu();
    menu.setAuthority("existing.authority");
    menu.setPath("/some/path");
    menu.prePersist();
    assertThat(menu.getAuthority()).isEqualTo("existing.authority");
  }

  @Test
  void prePersist_doesNotSetAuthority_whenPathIsNull() {
    Menu menu = new Menu();
    menu.setPath(null);
    menu.prePersist();
    assertThat(menu.getAuthority()).isNull();
    assertThat(menu.getDisabled()).isFalse();
  }

  @Test
  void prePersist_doesNotOverrideDisabled_whenAlreadySet() {
    Menu menu = new Menu();
    menu.setPath("/p");
    menu.setDisabled(true);
    menu.prePersist();
    assertThat(menu.getDisabled()).isTrue();
  }

  @Test
  void menu_setterAndGetter() {
    Menu menu = new Menu();
    menu.setLabel("Dashboard");
    menu.setTitle("My Dashboard");
    menu.setIcon("icon-dashboard");
    menu.setSort(1);
    menu.setType("page");
    menu.setComponent("dashboard/index");
    menu.setDanger(true);
    menu.setParent("parent-id");

    assertThat(menu.getLabel()).isEqualTo("Dashboard");
    assertThat(menu.getTitle()).isEqualTo("My Dashboard");
    assertThat(menu.getIcon()).isEqualTo("icon-dashboard");
    assertThat(menu.getSort()).isEqualTo(1);
    assertThat(menu.getType()).isEqualTo("page");
    assertThat(menu.getComponent()).isEqualTo("dashboard/index");
    assertThat(menu.getDanger()).isTrue();
    assertThat(menu.getParent()).isEqualTo("parent-id");
  }
}
