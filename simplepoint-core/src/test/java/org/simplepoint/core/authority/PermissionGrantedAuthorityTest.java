package org.simplepoint.core.authority;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PermissionGrantedAuthorityTest {

  @Test
  void constructor_setsAllFields() {
    PermissionGrantedAuthority authority =
        new PermissionGrantedAuthority("perm-id", "perm:read", "role-id", "ROLE_USER");
    assertThat(authority.getId()).isEqualTo("perm-id");
    assertThat(authority.getAuthority()).isEqualTo("perm:read");
    assertThat(authority.getRoleId()).isEqualTo("role-id");
    assertThat(authority.getRoleAuthority()).isEqualTo("ROLE_USER");
  }

  @Test
  void getAuthority_returnsAuthorityString() {
    PermissionGrantedAuthority authority =
        new PermissionGrantedAuthority("id", "perm:write", "rid", "ROLE_ADMIN");
    assertThat(authority.getAuthority()).isEqualTo("perm:write");
  }

  @Test
  void constructor_withNullValues_acceptsNulls() {
    PermissionGrantedAuthority authority =
        new PermissionGrantedAuthority(null, null, null, null);
    assertThat(authority.getId()).isNull();
    assertThat(authority.getAuthority()).isNull();
    assertThat(authority.getRoleId()).isNull();
    assertThat(authority.getRoleAuthority()).isNull();
  }
}
