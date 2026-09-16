package org.simplepoint.cloud.oauth.server.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

@ExtendWith(MockitoExtension.class)
class RecoveringOidcLogoutFailureHandlerTest {

  private static final String CLIENT_ID = "dashboard-client";

  private static final String LOGOUT_REDIRECT_URI = "https://dashboard.example.test";

  @Mock
  private RegisteredClientRepository registeredClientRepository;

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void redirectsAndClearsSessionForRegisteredClientAndRedirectUri() throws Exception {
    when(registeredClientRepository.findByClientId(CLIENT_ID)).thenReturn(registeredClient());
    MockHttpServletRequest request = logoutRequest(LOGOUT_REDIRECT_URI);
    MockHttpSession session = (MockHttpSession) request.getSession();
    MockHttpServletResponse response = new MockHttpServletResponse();
    SecurityContextHolder.getContext().setAuthentication(
        new TestingAuthenticationToken("user", "password")
    );

    handler().onAuthenticationFailure(request, response, invalidRequest());

    assertThat(response.getStatus()).isEqualTo(302);
    assertThat(response.getRedirectedUrl()).isEqualTo(LOGOUT_REDIRECT_URI);
    assertThat(session.isInvalid()).isTrue();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    assertThat(response.getHeaders("Set-Cookie"))
        .anyMatch(header -> header.startsWith("JSESSIONID="));
  }

  @Test
  void rejectsUnregisteredRedirectUriWithoutClearingSession() throws Exception {
    when(registeredClientRepository.findByClientId(CLIENT_ID)).thenReturn(registeredClient());
    MockHttpServletRequest request = logoutRequest("https://attacker.example.test");
    MockHttpSession session = (MockHttpSession) request.getSession();
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler().onAuthenticationFailure(request, response, invalidRequest());

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getRedirectedUrl()).isNull();
    assertThat(session.isInvalid()).isFalse();
  }

  @Test
  void preservesProtocolErrorWhenClientIdIsMissing() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/connect/logout");
    request.addParameter("post_logout_redirect_uri", LOGOUT_REDIRECT_URI);
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler().onAuthenticationFailure(request, response, invalidRequest());

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getRedirectedUrl()).isNull();
    verifyNoInteractions(registeredClientRepository);
  }

  @Test
  void rejectsRecoveryWithoutAnIdTokenHint() throws Exception {
    when(registeredClientRepository.findByClientId(CLIENT_ID)).thenReturn(registeredClient());
    MockHttpServletRequest request = logoutRequest(LOGOUT_REDIRECT_URI);
    request.removeParameter("id_token_hint");
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler().onAuthenticationFailure(request, response, invalidRequest());

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getRedirectedUrl()).isNull();
  }

  private RecoveringOidcLogoutFailureHandler handler() {
    return new RecoveringOidcLogoutFailureHandler(registeredClientRepository);
  }

  private MockHttpServletRequest logoutRequest(final String redirectUri) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/connect/logout");
    request.addParameter("client_id", CLIENT_ID);
    request.addParameter("id_token_hint", "expired-or-revoked-id-token");
    request.addParameter("post_logout_redirect_uri", redirectUri);
    return request;
  }

  private RegisteredClient registeredClient() {
    return RegisteredClient.withId("registered-client-id")
        .clientId(CLIENT_ID)
        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("https://dashboard.example.test/login/oauth2/code/oidc")
        .postLogoutRedirectUri(LOGOUT_REDIRECT_URI)
        .scope("openid")
        .build();
  }

  private OAuth2AuthenticationException invalidRequest() {
    return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_REQUEST));
  }
}
