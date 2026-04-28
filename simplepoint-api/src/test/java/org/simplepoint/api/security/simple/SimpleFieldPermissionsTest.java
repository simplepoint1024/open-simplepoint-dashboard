package org.simplepoint.api.security.simple;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SimpleFieldPermissionsTest {

  @Test
  void getFieldName_returnsFieldName_whenResourceContainsHash() {
    SimpleFieldPermissions p = new SimpleFieldPermissions("ROLE_ADMIN", "User#username", "READ");
    assertThat(p.getFieldName()).isEqualTo("username");
  }

  @Test
  void getFieldName_returnsNull_whenResourceIsNull() {
    SimpleFieldPermissions p = new SimpleFieldPermissions("ROLE_ADMIN", null, "READ");
    assertThat(p.getFieldName()).isNull();
  }

  @Test
  void getFieldName_returnsNull_whenResourceHasNoHash() {
    SimpleFieldPermissions p = new SimpleFieldPermissions("ROLE_ADMIN", "UserResource", "READ");
    assertThat(p.getFieldName()).isNull();
  }

  @Test
  void recordFields_areAccessible() {
    SimpleFieldPermissions p = new SimpleFieldPermissions("ROLE_USER", "Order#status", "WRITE");
    assertThat(p.authority()).isEqualTo("ROLE_USER");
    assertThat(p.resource()).isEqualTo("Order#status");
    assertThat(p.action()).isEqualTo("WRITE");
  }

  @Test
  void recordEquality_worksCorrectly() {
    SimpleFieldPermissions p1 = new SimpleFieldPermissions("A", "B#c", "R");
    SimpleFieldPermissions p2 = new SimpleFieldPermissions("A", "B#c", "R");
    assertThat(p1).isEqualTo(p2);
  }
}
