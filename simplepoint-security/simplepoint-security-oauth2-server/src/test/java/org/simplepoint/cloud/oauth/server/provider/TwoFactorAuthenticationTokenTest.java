package org.simplepoint.cloud.oauth.server.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class TwoFactorAuthenticationTokenTest {

  // ── unauthenticated constructor ───────────────────────────────────────────

  @Test
  void unauthenticatedTokenShouldNotBeAuthenticated() {
    TwoFactorAuthenticationToken token = new TwoFactorAuthenticationToken("alice", "123456");

    assertThat(token.isAuthenticated()).isFalse();
  }

  @Test
  void unauthenticatedTokenShouldReturnPrincipal() {
    TwoFactorAuthenticationToken token = new TwoFactorAuthenticationToken("alice", "123456");

    assertThat(token.getPrincipal()).isEqualTo("alice");
  }

  @Test
  void unauthenticatedTokenShouldReturnVerificationCodeAsCredentials() {
    TwoFactorAuthenticationToken token = new TwoFactorAuthenticationToken("alice", "999888");

    assertThat(token.getCredentials()).isEqualTo("999888");
  }

  @Test
  void unauthenticatedTokenShouldHaveEmptyAuthorities() {
    TwoFactorAuthenticationToken token = new TwoFactorAuthenticationToken("alice", "123456");

    assertThat(token.getAuthorities()).isEmpty();
  }

  // ── authenticated constructor ─────────────────────────────────────────────

  @Test
  void authenticatedTokenShouldBeAuthenticated() {
    var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
    TwoFactorAuthenticationToken token =
        new TwoFactorAuthenticationToken("alice", "123456", authorities);

    assertThat(token.isAuthenticated()).isTrue();
  }

  @Test
  void authenticatedTokenShouldReturnPrincipal() {
    var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
    TwoFactorAuthenticationToken token =
        new TwoFactorAuthenticationToken("alice", "123456", authorities);

    assertThat(token.getPrincipal()).isEqualTo("alice");
  }

  @Test
  void authenticatedTokenShouldReturnVerificationCodeAsCredentials() {
    var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
    TwoFactorAuthenticationToken token =
        new TwoFactorAuthenticationToken("alice", "654321", authorities);

    assertThat(token.getCredentials()).isEqualTo("654321");
  }

  @Test
  void authenticatedTokenShouldCarryAuthorities() {
    var authorities = List.of(
        new SimpleGrantedAuthority("ROLE_USER"),
        new SimpleGrantedAuthority("ROLE_ADMIN"));
    TwoFactorAuthenticationToken token =
        new TwoFactorAuthenticationToken("alice", "123456", authorities);

    assertThat(token.getAuthorities()).hasSize(2);
  }

  // ── null-safe ─────────────────────────────────────────────────────────────

  @Test
  void tokenShouldAcceptNullVerificationCode() {
    TwoFactorAuthenticationToken token = new TwoFactorAuthenticationToken("alice", null);

    assertThat(token.getCredentials()).isNull();
  }
}
