package org.simplepoint.cloud.oauth.server.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.cloud.oauth.server.event.LoginAuditEventPublisher;
import org.simplepoint.cloud.oauth.server.handler.LoginAuthenticationSuccessHandler;
import org.simplepoint.cloud.oauth.server.provider.PendingMfaAuthentication;
import org.simplepoint.plugin.rbac.authentication.MfaVerificationService;
import org.simplepoint.security.entity.User;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.ExtendedModelMap;

class TwoFactorControllerTest {
  MfaVerificationService verification = mock(MfaVerificationService.class);
  LoginAuthenticationSuccessHandler success = mock(LoginAuthenticationSuccessHandler.class);
  LoginAuditEventPublisher audit = mock(LoginAuditEventPublisher.class);
  TwoFactorController controller = new TwoFactorController(verification, success, audit);
  MockHttpServletRequest request = new MockHttpServletRequest("POST", "/two-factor/verify");
  MockHttpServletResponse response = new MockHttpServletResponse();
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }
  @Test void onlyPendingUnexpiredChallengeCanBeCompleted() throws Exception {
    assertThat(controller.verify("123456", new ExtendedModelMap(), request, response)).isEqualTo("redirect:/login");
    SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("alice", null, java.util.List.of()));
    assertThat(controller.showVerificationPage()).isEqualTo("redirect:/login");
    SecurityContextHolder.getContext().setAuthentication(new PendingMfaAuthentication("alice", 0, Instant.EPOCH));
    assertThat(controller.verify("123456", new ExtendedModelMap(), request, response)).isEqualTo("redirect:/login");
    verifyNoInteractions(verification, success, audit);
  }
  @Test void validFactorUsesSanitizedIdentityAndFailureNeverCompletesLogin() throws Exception {
    var pending = new PendingMfaAuthentication("alice", 0, Instant.now().plusSeconds(60));
    SecurityContextHolder.getContext().setAuthentication(pending);
    when(verification.verify(pending.challenge(), "wrong")).thenThrow(new BadCredentialsException("Invalid code"));
    assertThat(controller.verify("wrong", new ExtendedModelMap(), request, response)).isEqualTo("two-factor-verify");
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(pending);
    verifyNoInteractions(success);
    verify(audit).publishFailure(eq(request), eq(pending), any(BadCredentialsException.class));
    User user = new User(); user.setId("alice"); user.setPassword("hash"); user.setTwoFactorSecret("ciphertext");
    when(verification.verify(pending.challenge(), "123456")).thenReturn(user);
    assertThat(controller.verify("123456", new ExtendedModelMap(), request, response)).isNull();
    verify(success).complete(eq(request), eq(response), argThat(auth -> auth.isAuthenticated()
        && ((User) auth.getPrincipal()).getPassword() == null
        && ((User) auth.getPrincipal()).getTwoFactorSecret() == null && auth.getCredentials() == null));
  }
}
