package org.simplepoint.cloud.oauth.server.handler;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.cloud.oauth.server.client.ExternalIdentityLinkFlow;
import org.simplepoint.cloud.oauth.server.event.LoginAuditEventPublisher;
import org.simplepoint.cloud.oauth.server.provider.PendingMfaAuthentication;
import org.simplepoint.plugin.rbac.authentication.LoginProtection;
import org.simplepoint.plugin.rbac.core.api.service.UserManagementService;
import org.simplepoint.security.entity.User;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

class LoginAuthenticationSuccessHandlerTest {
  LoginAuditEventPublisher audit = mock(LoginAuditEventPublisher.class);
  ExternalIdentityLinkFlow links = mock(ExternalIdentityLinkFlow.class);
  LoginAuthenticationSuccessHandler handler = new LoginAuthenticationSuccessHandler(audit,
      mock(UserManagementService.class), links, mock(LoginProtection.class));
  MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
  MockHttpServletResponse response = new MockHttpServletResponse();
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }
  @Test void passwordSuccessPersistsOnlyPendingIdentityAndDefersLinkAndAudit() throws Exception {
    User user = new User(); user.setId("alice"); user.setTwoFactorEnabled(true);
    user.setTwoFactorSecret(null); // Missing seed must not silently downgrade to password-only.
    var auth = UsernamePasswordAuthenticationToken.authenticated(user, "password", java.util.List.of());
    handler.onAuthenticationSuccess(request, response, auth);
    var stored = (SecurityContext) request.getSession().getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    assertThat(stored.getAuthentication()).isInstanceOf(PendingMfaAuthentication.class);
    assertThat(stored.getAuthentication().isAuthenticated()).isFalse();
    assertThat(stored.getAuthentication().getCredentials()).isNull();
    assertThat(stored.getAuthentication().getAuthorities()).isEmpty();
    assertThat(response.getRedirectedUrl()).isEqualTo("/two-factor/verify");
    verifyNoInteractions(links, audit);
  }
  @Test void auditStorageFailureCannotLeaveAnAuthenticatedSession() {
    User user = new User(); user.setId("alice"); user.setTwoFactorEnabled(false);
    var auth = UsernamePasswordAuthenticationToken.authenticated(user, "password", java.util.List.of());
    doThrow(new IllegalStateException("database unavailable")).when(audit).publishSuccess(any(), any());
    assertThatThrownBy(() -> handler.onAuthenticationSuccess(request, response, auth)).isInstanceOf(IllegalStateException.class);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    assertThat(request.getSession(false)).isNull();
  }
  @Test void missingCsrfInitializationCannotLeaveAnAuthenticatedSession() {
    User user = new User(); user.setId("alice"); user.setTwoFactorEnabled(false);
    var auth = UsernamePasswordAuthenticationToken.authenticated(user, null, java.util.List.of());
    assertThatThrownBy(() -> handler.onAuthenticationSuccess(request, response, auth))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Browser session protection was not initialized");
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    assertThat(request.getSession(false)).isNull();
    assertThat(response.getRedirectedUrl()).isNull();
  }
}
