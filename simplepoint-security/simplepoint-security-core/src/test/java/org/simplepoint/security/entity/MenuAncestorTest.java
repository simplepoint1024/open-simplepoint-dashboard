package org.simplepoint.security.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.simplepoint.security.entity.id.MenuAncestorId;

class MenuAncestorTest {

  @Test
  void menuAncestorId_getterAndSetter() {
    MenuAncestorId id = new MenuAncestorId();
    id.setChildId("child1");
    id.setAncestorId("ancestor1");
    assertThat(id.getChildId()).isEqualTo("child1");
    assertThat(id.getAncestorId()).isEqualTo("ancestor1");
  }

  @Test
  void menuAncestorId_equality() {
    MenuAncestorId id1 = new MenuAncestorId();
    id1.setChildId("c");
    id1.setAncestorId("a");

    MenuAncestorId id2 = new MenuAncestorId();
    id2.setChildId("c");
    id2.setAncestorId("a");

    assertThat(id1).isEqualTo(id2);
    assertThat(id1.hashCode()).isEqualTo(id2.hashCode());
  }

  @Test
  void menuAncestor_getterAndSetter() {
    MenuAncestor ancestor = new MenuAncestor();
    ancestor.setChildId("child1");
    ancestor.setAncestorId("ancestor1");
    assertThat(ancestor.getChildId()).isEqualTo("child1");
    assertThat(ancestor.getAncestorId()).isEqualTo("ancestor1");
  }

  @Test
  void menuAncestor_equality() {
    MenuAncestor a1 = new MenuAncestor();
    a1.setChildId("c");
    a1.setAncestorId("a");

    MenuAncestor a2 = new MenuAncestor();
    a2.setChildId("c");
    a2.setAncestorId("a");

    assertThat(a1).isEqualTo(a2);
  }
}
