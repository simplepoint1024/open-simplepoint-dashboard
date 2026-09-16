package org.simplepoint.cloud.oauth.server.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withForbiddenRequest;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityMatchStrategy;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityProviderPreset;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityProviderProtocol;
import org.simplepoint.plugin.oidc.api.model.ResolvedExternalIdentityProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GithubEmailAttributeEnricherTest {

  @Test
  void emailPermissionFailureDoesNotDiscardValidGithubIdentity() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("https://api.github.com/user/emails"))
        .andRespond(withForbiddenRequest());
    GithubEmailAttributeEnricher enricher =
        new GithubEmailAttributeEnricher(builder.build());
    Map<String, Object> source = Map.of("id", 42, "login", "octocat");

    Map<String, Object> result = enricher.enrich(
        request(), provider(), source
    );

    assertThat(result).containsAllEntriesOf(source);
    assertThat(result).doesNotContainKey("email_verified");
    server.verify();
  }

  private OAuth2UserRequest request() {
    ClientRegistration registration = ClientRegistration
        .withRegistrationId("github")
        .clientId("client")
        .clientSecret("secret")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("https://app.example.com/login/oauth2/code/github")
        .authorizationUri("https://github.com/login/oauth/authorize")
        .tokenUri("https://github.com/login/oauth/access_token")
        .userInfoUri("https://api.github.com/user")
        .userNameAttributeName("id")
        .clientName("GitHub")
        .build();
    OAuth2AccessToken token = new OAuth2AccessToken(
        OAuth2AccessToken.TokenType.BEARER,
        "token",
        Instant.now(),
        Instant.now().plusSeconds(300)
    );
    return new OAuth2UserRequest(registration, token);
  }

  private ResolvedExternalIdentityProvider provider() {
    return new ResolvedExternalIdentityProvider(
        "provider-1", "github", "GitHub", ExternalIdentityProviderPreset.GITHUB,
        ExternalIdentityProviderProtocol.OAUTH2, null,
        "https://github.com/login/oauth/authorize",
        "https://github.com/login/oauth/access_token",
        "https://api.github.com/user", null, "client", "secret",
        "client_secret_basic", Set.of("read:user", "user:email"), "id", "id",
        "email", "email_verified", ExternalIdentityMatchStrategy.VERIFIED_EMAIL,
        true, false, Instant.parse("2026-01-01T00:00:00Z")
    );
  }
}
