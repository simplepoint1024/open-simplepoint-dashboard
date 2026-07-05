package org.simplepoint.cloud.oauth.server.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.oidc.OidcScopes;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.security.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcUserInfoAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcUserInfoAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class OpenidOidcUserInfoAuthenticationTest {

  @Test
  void apply_openidSubjectByUserIdLoadsUserById() {
    UsersService usersService = org.mockito.Mockito.mock(UsersService.class);
    User user = user("u1", "user@example.com");
    when(usersService.findByIdForAuthorization("u1")).thenReturn(Optional.of(user));

    var userInfo = new OpenidOidcUserInfoAuthentication(usersService)
        .apply(context("u1", OidcScopes.OPENID, OidcScopes.PROFILE, OidcScopes.EMAIL));

    assertThat(userInfo.getSubject()).isEqualTo("u1");
    assertThat(userInfo.getClaims()).containsEntry("email", "user@example.com");
    verify(usersService, never()).loadUserByUsername("u1");
  }

  @Test
  void apply_legacyLoginSubjectFallsBackAndNormalizesSubject() {
    UsersService usersService = org.mockito.Mockito.mock(UsersService.class);
    User user = user("u1", "simplepoint@mail.com");
    when(usersService.findByIdForAuthorization("simplepoint@mail.com")).thenReturn(Optional.empty());
    when(usersService.loadUserByUsername("simplepoint@mail.com")).thenReturn(user);

    var userInfo = new OpenidOidcUserInfoAuthentication(usersService)
        .apply(context("simplepoint@mail.com", OidcScopes.OPENID, OidcScopes.EMAIL));

    assertThat(userInfo.getSubject()).isEqualTo("u1");
    assertThat(userInfo.getClaims()).containsEntry("email", "simplepoint@mail.com");
  }

  @Test
  void apply_unknownOpenidSubjectKeepsSubjectWithoutFailing() {
    UsersService usersService = org.mockito.Mockito.mock(UsersService.class);
    when(usersService.findByIdForAuthorization("simplepoint-service-common")).thenReturn(Optional.empty());
    when(usersService.loadUserByUsername("simplepoint-service-common"))
        .thenThrow(new UsernameNotFoundException("not found"));

    var userInfo = new OpenidOidcUserInfoAuthentication(usersService)
        .apply(context("simplepoint-service-common", OidcScopes.OPENID));

    assertThat(userInfo.getSubject()).isEqualTo("simplepoint-service-common");
    assertThat(userInfo.getClaims()).containsOnlyKeys("sub");
  }

  private static User user(String id, String email) {
    User user = new User();
    user.setId(id);
    user.setEmail(email);
    user.setEmailVerified(true);
    return user;
  }

  private static OidcUserInfoAuthenticationContext context(String subject, String... scopes) {
    Jwt jwt = Jwt.withTokenValue("token")
        .header("alg", "none")
        .subject(subject)
        .build();
    JwtAuthenticationToken principal = new JwtAuthenticationToken(jwt, authorities(scopes), subject);
    OAuth2AccessToken accessToken = new OAuth2AccessToken(
        OAuth2AccessToken.TokenType.BEARER,
        "token",
        Instant.now(),
        Instant.now().plusSeconds(60)
    );
    RegisteredClient registeredClient = RegisteredClient.withId("client-id")
        .clientId("client")
        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("http://localhost/callback")
        .scope(OidcScopes.OPENID)
        .build();
    OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(registeredClient)
        .principalName(subject)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .authorizedScopes(Set.of(scopes))
        .accessToken(accessToken)
        .build();
    return OidcUserInfoAuthenticationContext.with(new OidcUserInfoAuthenticationToken(principal))
        .accessToken(accessToken)
        .authorization(authorization)
        .build();
  }

  private static Collection<GrantedAuthority> authorities(String... scopes) {
    return List.of(scopes).stream()
        .map(OidcScopes::getScopeAuthority)
        .map(authority -> (GrantedAuthority) authority)
        .toList();
  }
}
