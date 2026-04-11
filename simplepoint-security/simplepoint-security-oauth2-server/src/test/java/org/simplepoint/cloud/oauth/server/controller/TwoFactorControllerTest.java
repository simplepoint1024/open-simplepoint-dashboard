package org.simplepoint.cloud.oauth.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.cloud.oauth.server.event.LoginAuditEventPublisher;
import org.simplepoint.cloud.oauth.server.handler.LoginAuthenticationSuccessHandler;
import org.simplepoint.cloud.oauth.server.provider.TwoFactorAuthenticationProvider;
import org.simplepoint.cloud.oauth.server.provider.TwoFactorAuthenticationToken;
import org.simplepoint.security.entity.User;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.ExtendedModelMap;

@ExtendWith(MockitoExtension.class)
class TwoFactorControllerTest {

  @Mock
  private TwoFactorAuthenticationProvider twoFactorAuthenticationProvider;

  @Mock
  private LoginAuthenticationSuccessHandler authenticationSuccessHandler;

  @Mock
  private LoginAuditEventPublisher loginAuditEventPublisher;

  private TwoFactorController controller;

  @BeforeEach
  void setUp() {
    controller = new TwoFactorController(
        twoFactorAuthenticationProvider,
        authenticationSuccessHandler,
        loginAuditEventPublisher
    );
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void verifyDelegatesToDedicatedTwoFactorProvider() throws Exception {
    User user = twoFactorEnabledUser();
    Authentication currentAuthentication = new UsernamePasswordAuthenticationToken(
        user,
        user.getPassword(),
        user.getAuthorities()
    );
    Authentication verifiedAuthentication = new UsernamePasswordAuthenticationToken(
        user,
        user.getPassword(),
        user.getAuthorities()
    );
    SecurityContextHolder.getContext().setAuthentication(currentAuthentication);
    when(twoFactorAuthenticationProvider.authenticate(any(TwoFactorAuthenticationToken.class)))
        .thenReturn(verifiedAuthentication);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/two-factor/verify");
    MockHttpServletResponse response = new MockHttpServletResponse();
    ExtendedModelMap model = new ExtendedModelMap();

    String view = controller.verify("123456", model, request, response);

    assertThat(view).isNull();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(verifiedAuthentication);
    verify(twoFactorAuthenticationProvider).authenticate(argThat(authentication ->
        authentication instanceof TwoFactorAuthenticationToken token
            && token.getPrincipal() == user
            && "123456".equals(token.getCredentials())
    ));
    verify(authenticationSuccessHandler).onAuthenticationSuccessDelegate(request, response, verifiedAuthentication);
    verifyNoInteractions(loginAuditEventPublisher);
  }

  @Test
  void verifyRejectsUnavailableTwoFactorAuthentication() {
    User user = twoFactorEnabledUser();
    Authentication currentAuthentication = new UsernamePasswordAuthenticationToken(
        user,
        user.getPassword(),
        user.getAuthorities()
    );
    SecurityContextHolder.getContext().setAuthentication(currentAuthentication);
    when(twoFactorAuthenticationProvider.authenticate(any(TwoFactorAuthenticationToken.class))).thenReturn(null);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/two-factor/verify");
    MockHttpServletResponse response = new MockHttpServletResponse();
    ExtendedModelMap model = new ExtendedModelMap();

    String view = controller.verify("123456", model, request, response);

    assertThat(view).isEqualTo("two-factor-verify");
    assertThat(model.getAttribute("error")).isEqualTo("Invalid authentication code");
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(currentAuthentication);
    verify(loginAuditEventPublisher).publishFailure(eq(request), eq(currentAuthentication), any(BadCredentialsException.class));
  }

  private static User twoFactorEnabledUser() {
    User user = new User();
    user.setUsername("demo");
    user.setPassword("secret");
    user.setTwoFactorEnabled(Boolean.TRUE);
    user.setTwoFactorSecret("SECRET");
    return user;
  }
}
