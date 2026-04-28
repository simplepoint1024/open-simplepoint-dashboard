/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.simplepoint.core.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class OidcScopesTest {

  @Test
  void canInstantiate() {
    assertThat(new OidcScopes()).isNotNull();
  }

  @Test
  void constants_haveExpectedValues() {
    assertThat(OidcScopes.SCOPE_PREFIX).isEqualTo("SCOPE_");
    assertThat(OidcScopes.OPENID).isEqualTo("openid");
    assertThat(OidcScopes.PROFILE).isEqualTo("profile");
    assertThat(OidcScopes.EMAIL).isEqualTo("email");
    assertThat(OidcScopes.PHONE).isEqualTo("phone");
    assertThat(OidcScopes.ADDRESS).isEqualTo("address");
    assertThat(OidcScopes.ROLES).isEqualTo("roles");
    assertThat(OidcScopes.GROUPS).isEqualTo("groups");
    assertThat(OidcScopes.TENANT).isEqualTo("tenant");
    assertThat(OidcScopes.ORGANS).isEqualTo("organs");
  }

  @Test
  void getScopeAuthority_prefixesScope() {
    SimpleGrantedAuthority authority = OidcScopes.getScopeAuthority("openid");
    assertThat(authority.getAuthority()).isEqualTo("SCOPE_openid");
  }

  @Test
  void getScopeAuthority_worksForAllKnownScopes() {
    assertThat(OidcScopes.getScopeAuthority(OidcScopes.PROFILE).getAuthority())
        .isEqualTo("SCOPE_profile");
    assertThat(OidcScopes.getScopeAuthority(OidcScopes.EMAIL).getAuthority())
        .isEqualTo("SCOPE_email");
  }
}
