package org.simplepoint.plugin.oidc.service.initialize;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

class ClientRegistrationInitializeTest {

  @Test
  void buildRegisteredClientAppliesDefaultTokenAndConsentSettings() {
    Oauth2ClientInitializeProperties properties = new Oauth2ClientInitializeProperties();
    ClientRegistrationInitialize initializer = new ClientRegistrationInitialize(properties, null);

    RegisteredClient client = initializer.buildRegisteredClient(registration(), provider());

    assertThat(client.getClientSettings().isRequireAuthorizationConsent()).isTrue();
    assertThat(client.getClientSettings().isRequireProofKey()).isTrue();
    assertThat(client.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(Duration.ofMinutes(30));
    assertThat(client.getTokenSettings().getRefreshTokenTimeToLive()).isEqualTo(Duration.ofHours(8));
    assertThat(client.getAuthorizationGrantTypes())
        .containsExactlyInAnyOrder(AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN);
  }

  @Test
  void buildRegisteredClientAllowsRegistrationLevelOverrides() {
    Oauth2ClientInitializeProperties properties = new Oauth2ClientInitializeProperties();
    ClientRegistrationInitialize initializer = new ClientRegistrationInitialize(properties, null);
    Oauth2ClientInitializeProperties.Registration registration = registration();
    registration.setRequireAuthorizationConsent(false);
    registration.setRequireProofKey(false);
    registration.setAccessTokenTimeToLive(Duration.ofMinutes(45));
    registration.setRefreshTokenTimeToLive(Duration.ofHours(12));
    registration.setReuseRefreshTokens(false);

    RegisteredClient client = initializer.buildRegisteredClient(registration, provider());

    assertThat(client.getClientSettings().isRequireAuthorizationConsent()).isFalse();
    assertThat(client.getClientSettings().isRequireProofKey()).isFalse();
    assertThat(client.getTokenSettings().isReuseRefreshTokens()).isFalse();
    assertThat(client.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(Duration.ofMinutes(45));
    assertThat(client.getTokenSettings().getRefreshTokenTimeToLive()).isEqualTo(Duration.ofHours(12));
  }

  private Oauth2ClientInitializeProperties.Registration registration() {
    Oauth2ClientInitializeProperties.Registration registration =
        new Oauth2ClientInitializeProperties.Registration();
    registration.setClientId("simplepoint-client");
    registration.setClientSecret("secret");
    registration.setClientName("SimplePoint");
    registration.setClientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue());
    registration.setAuthorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE.getValue());
    registration.setRedirectUri("http://localhost:8080/login/oauth2/code/oidc");
    registration.setScope(Set.of("openid", "profile"));
    return registration;
  }

  private Oauth2ClientInitializeProperties.Provider provider() {
    Oauth2ClientInitializeProperties.Provider provider = new Oauth2ClientInitializeProperties.Provider();
    provider.setJwkSetUri("http://localhost:9000/oauth2/jwks");
    return provider;
  }
}
