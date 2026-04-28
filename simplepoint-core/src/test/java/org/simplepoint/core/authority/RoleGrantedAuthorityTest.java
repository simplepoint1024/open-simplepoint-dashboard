package org.simplepoint.core.authority;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RoleGrantedAuthorityTest {

  @Test
  void constructor_setsAllFields() {
    RoleGrantedAuthority authority = new RoleGrantedAuthority("role-id-1", "ROLE_ADMIN");
    assertThat(authority.getId()).isEqualTo("role-id-1");
    assertThat(authority.getAuthority()).isEqualTo("ROLE_ADMIN");
  }

  @Test
  void getAuthority_returnsAuthorityString() {
    RoleGrantedAuthority authority = new RoleGrantedAuthority("id", "ROLE_USER");
    assertThat(authority.getAuthority()).isEqualTo("ROLE_USER");
  }

  @Test
  void constructor_withNullValues_acceptsNulls() {
    RoleGrantedAuthority authority = new RoleGrantedAuthority(null, null);
    assertThat(authority.getId()).isNull();
    assertThat(authority.getAuthority()).isNull();
  }
}
