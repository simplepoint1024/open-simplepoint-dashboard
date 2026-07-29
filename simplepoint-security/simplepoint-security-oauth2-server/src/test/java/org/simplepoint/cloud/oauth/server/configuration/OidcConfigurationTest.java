package org.simplepoint.cloud.oauth.server.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.security.decorator.TokenDecorator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

class OidcConfigurationTest {

  @Test
  void jwtCustomizer_accessTokenContainsConfiguredAndClientAudiences() {
    OidcConfiguration configuration = new OidcConfiguration();
    OAuth2TokenCustomizer<JwtEncodingContext> customizer = configuration.jwtCustomizer(
        Set.<TokenDecorator>of(),
        mock(SessionRegistry.class),
        "simplepoint-api"
    );

    RegisteredClient registeredClient = RegisteredClient.withId("client-id")
        .clientId("simplepoint-client")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("http://localhost/callback")
        .build();
    AuthorizationServerContext authorizationServerContext = new AuthorizationServerContext() {
      @Override
      public String getIssuer() {
        return "http://localhost:2999";
      }

      @Override
      public AuthorizationServerSettings getAuthorizationServerSettings() {
        return AuthorizationServerSettings.builder().build();
      }
    };
    JwtEncodingContext context = JwtEncodingContext.with(JwsHeader.with(SignatureAlgorithm.RS256), JwtClaimsSet.builder())
        .registeredClient(registeredClient)
        .principal(new UsernamePasswordAuthenticationToken("user", "password", AuthorityUtils.NO_AUTHORITIES))
        .authorizationServerContext(authorizationServerContext)
        .authorizedScopes(Set.of("openid"))
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .tokenType(OAuth2TokenType.ACCESS_TOKEN)
        .build();

    customizer.customize(context);

    assertThat(context.getJwsHeader().build().getAlgorithm()).isEqualTo(SignatureAlgorithm.PS256);
    assertThat(context.getClaims().build().getAudience())
        .containsExactly("simplepoint-api", "simplepoint-client");
    assertThat(context.getClaims().build().getClaims())
        .containsEntry("aud", context.getClaims().build().getAudience());
  }

  @Test
  void jwtCustomizer_accessTokenUsesExactAuthorizedResourceAudience() {
    OidcConfiguration configuration = new OidcConfiguration();
    OAuth2TokenCustomizer<JwtEncodingContext> customizer = configuration.jwtCustomizer(
        Set.<TokenDecorator>of(),
        mock(SessionRegistry.class),
        "simplepoint-api",
        "http://localhost:8080/mcp/"
    );
    String resource = "http://localhost:8080/mcp/demo";
    RegisteredClient registeredClient = registeredClient();
    OAuth2AuthorizationRequest authorizationRequest =
        OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri("http://localhost:2999/oauth2/authorize")
            .clientId(registeredClient.getClientId())
            .redirectUri("http://localhost/callback")
            .additionalParameters(parameters ->
                parameters.put("resource", resource))
            .build();
    OAuth2Authorization authorization = OAuth2Authorization
        .withRegisteredClient(registeredClient)
        .principalName("user")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .attribute(
            OAuth2AuthorizationRequest.class.getName(),
            authorizationRequest
        )
        .build();
    OAuth2AuthorizationCodeAuthenticationToken grant =
        new OAuth2AuthorizationCodeAuthenticationToken(
            "code",
            new UsernamePasswordAuthenticationToken(
                registeredClient.getClientId(),
                "secret"
            ),
            "http://localhost/callback",
            Map.of("resource", resource)
        );
    JwtEncodingContext context = context(registeredClient, authorization, grant);

    customizer.customize(context);

    assertThat(context.getClaims().build().getAudience())
        .containsExactly(resource, "simplepoint-client");
  }

  @Test
  void jwtCustomizer_rejectsTokenRequestForDifferentResource() {
    OidcConfiguration configuration = new OidcConfiguration();
    OAuth2TokenCustomizer<JwtEncodingContext> customizer = configuration.jwtCustomizer(
        Set.<TokenDecorator>of(),
        mock(SessionRegistry.class),
        "simplepoint-api",
        "http://localhost:8080/mcp/"
    );
    RegisteredClient registeredClient = registeredClient();
    OAuth2AuthorizationRequest authorizationRequest =
        OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri("http://localhost:2999/oauth2/authorize")
            .clientId(registeredClient.getClientId())
            .redirectUri("http://localhost/callback")
            .additionalParameters(parameters ->
                parameters.put("resource", "http://localhost:8080/mcp/demo"))
            .build();
    OAuth2Authorization authorization = OAuth2Authorization
        .withRegisteredClient(registeredClient)
        .principalName("user")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .attribute(
            OAuth2AuthorizationRequest.class.getName(),
            authorizationRequest
        )
        .build();
    OAuth2AuthorizationCodeAuthenticationToken grant =
        new OAuth2AuthorizationCodeAuthenticationToken(
            "code",
            new UsernamePasswordAuthenticationToken(
                registeredClient.getClientId(),
                "secret"
            ),
            "http://localhost/callback",
            Map.of("resource", "http://localhost:8080/mcp/other")
        );
    JwtEncodingContext context = context(registeredClient, authorization, grant);

    assertThatThrownBy(() -> customizer.customize(context))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .satisfies(exception -> assertThat(
            ((OAuth2AuthenticationException) exception).getError().getErrorCode()
        ).isEqualTo("invalid_target"));
  }

  private static RegisteredClient registeredClient() {
    return RegisteredClient.withId("client-id")
        .clientId("simplepoint-client")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("http://localhost/callback")
        .build();
  }

  private static JwtEncodingContext context(
      final RegisteredClient registeredClient,
      final OAuth2Authorization authorization,
      final OAuth2AuthorizationCodeAuthenticationToken grant
  ) {
    AuthorizationServerContext authorizationServerContext =
        new AuthorizationServerContext() {
          @Override
          public String getIssuer() {
            return "http://localhost:2999";
          }

          @Override
          public AuthorizationServerSettings getAuthorizationServerSettings() {
            return AuthorizationServerSettings.builder().build();
          }
        };
    return JwtEncodingContext.with(
            JwsHeader.with(SignatureAlgorithm.RS256),
            JwtClaimsSet.builder()
        )
        .registeredClient(registeredClient)
        .principal(new UsernamePasswordAuthenticationToken(
            "user",
            "password",
            AuthorityUtils.NO_AUTHORITIES
        ))
        .authorizationServerContext(authorizationServerContext)
        .authorization(authorization)
        .authorizationGrant(grant)
        .authorizedScopes(Set.of("mcp.invoke"))
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .tokenType(OAuth2TokenType.ACCESS_TOKEN)
        .build();
  }
}
