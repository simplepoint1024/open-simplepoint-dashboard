package org.simplepoint.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthorizationResourceNamespacesTest {

  @Test
  void constants_useStandardScopePrefixes() {
    assertThat(AuthorizationResourceNamespaces.PLATFORM_PREFIX).isEqualTo("platform.");
    assertThat(AuthorizationResourceNamespaces.TENANT_PREFIX).isEqualTo("tenant.");
    assertThat(AuthorizationResourceNamespaces.SELF_PREFIX).isEqualTo("self.");
  }

  @Test
  void adminResources_useStandardNamespaces() {
    assertThat(AuthorizationResourceNamespaces.PLATFORM_ADMIN).isEqualTo("platform.admin");
    assertThat(AuthorizationResourceNamespaces.TENANT_ADMIN).isEqualTo("tenant.admin");
  }
}
