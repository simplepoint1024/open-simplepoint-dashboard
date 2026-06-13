package org.simplepoint.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthorizationPermissionNamespacesTest {

  @Test
  void constants_useStandardScopePrefixes() {
    assertThat(AuthorizationPermissionNamespaces.PLATFORM_PREFIX).isEqualTo("platform.");
    assertThat(AuthorizationPermissionNamespaces.TENANT_PREFIX).isEqualTo("tenant.");
    assertThat(AuthorizationPermissionNamespaces.SELF_PREFIX).isEqualTo("self.");
  }

  @Test
  void adminPermissions_useStandardNamespaces() {
    assertThat(AuthorizationPermissionNamespaces.PLATFORM_ADMIN).isEqualTo("platform.admin");
    assertThat(AuthorizationPermissionNamespaces.TENANT_ADMIN).isEqualTo("tenant.admin");
  }
}
