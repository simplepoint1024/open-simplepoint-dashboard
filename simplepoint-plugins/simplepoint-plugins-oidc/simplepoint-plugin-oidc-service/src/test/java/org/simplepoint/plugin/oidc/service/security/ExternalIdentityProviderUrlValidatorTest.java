package org.simplepoint.plugin.oidc.service.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class ExternalIdentityProviderUrlValidatorTest {

  @Test
  void acceptsPublicHttpsDestination() {
    assertThatNoException().isThrownBy(() ->
        ExternalIdentityProviderUrlValidator.validate(
            "https://accounts.google.com",
            "issuer",
            false,
            false
        ));
  }

  @Test
  void rejectsPlainHttpAndUnsafeUrlComponents() {
    assertThatThrownBy(() -> ExternalIdentityProviderUrlValidator.validate(
        "http://accounts.example.com",
        "issuer",
        false,
        false
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HTTPS");

    assertThatThrownBy(() -> ExternalIdentityProviderUrlValidator.validate(
        "https://user:password@accounts.example.com",
        "issuer",
        false,
        false
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不安全");
  }

  @Test
  void rejectsPrivateAndSpecialNetworkRanges() throws Exception {
    assertThat(ExternalIdentityProviderUrlValidator.isRestrictedAddress(
        InetAddress.getByName("127.0.0.1")
    )).isTrue();
    assertThat(ExternalIdentityProviderUrlValidator.isRestrictedAddress(
        InetAddress.getByName("10.1.2.3")
    )).isTrue();
    assertThat(ExternalIdentityProviderUrlValidator.isRestrictedAddress(
        InetAddress.getByName("100.64.0.1")
    )).isTrue();
    assertThat(ExternalIdentityProviderUrlValidator.isRestrictedAddress(
        InetAddress.getByName("198.18.0.1")
    )).isTrue();
    assertThat(ExternalIdentityProviderUrlValidator.isRestrictedAddress(
        InetAddress.getByName("8.8.8.8")
    )).isFalse();
  }

  @Test
  void permitsExplicitPrivateNetworkDevelopmentEndpoint() {
    assertThatNoException().isThrownBy(() ->
        ExternalIdentityProviderUrlValidator.validate(
            "http://localhost:8080/realms/example",
            "issuer",
            true,
            true
        ));
  }
}
