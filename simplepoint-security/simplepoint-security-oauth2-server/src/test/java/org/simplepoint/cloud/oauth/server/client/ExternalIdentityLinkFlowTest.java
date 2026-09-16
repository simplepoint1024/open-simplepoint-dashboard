package org.simplepoint.cloud.oauth.server.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityMatchStrategy;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityProviderPreset;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityProviderProtocol;
import org.simplepoint.plugin.oidc.api.model.ResolvedExternalIdentityProvider;
import org.simplepoint.plugin.oidc.api.service.ExternalIdentityProviderService;
import org.simplepoint.security.entity.User;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

@ExtendWith(MockitoExtension.class)
class ExternalIdentityLinkFlowTest {

  @Mock
  private ExternalIdentityProviderService providerService;

  @Mock
  private ExternalIdentityAccountLinker accountLinker;

  private ExternalIdentityLinkFlow flow;

  @BeforeEach
  void setUp() {
    flow = new ExternalIdentityLinkFlow(providerService, accountLinker);
  }

  @Test
  void explicitSettingsFlowBindsOnlyToTheAuthenticatedTarget() {
    MockHttpSession session = new MockHttpSession();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSession(session);
    User user = user("user-1");
    flow.begin(session, "user-1", provider(), true);
    when(accountLinker.linkToUser(
        provider(), "subject-1", "member@example.com", "user-1"
    )).thenReturn(user);

    User result = flow.resolve(request, provider(), Map.of(
        "sub", "subject-1",
        "email", "member@example.com"
    ));

    assertThat(result).isSameAs(user);
    assertThat(flow.pending(session)).isNull();
    ExternalIdentityLinkFlow.RecentlyLinked linked = flow.consumeRecentlyLinked(session);
    assertThat(linked.registrationId()).isEqualTo("example");
    assertThat(linked.gateway()).isTrue();
    assertThat(linked.returnToSettings()).isTrue();
  }

  @Test
  void failedAutomaticMatchCreatesShortLivedConfirmationState() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    OAuth2AuthenticationException failure = new OAuth2AuthenticationException(
        new OAuth2Error("external_account_not_linked")
    );
    when(accountLinker.link(provider(), Map.of(
        "sub", "subject-1", "email", "member@example.com"
    ))).thenThrow(failure);

    assertThatThrownBy(() -> flow.resolve(request, provider(), Map.of(
        "sub", "subject-1", "email", "member@example.com"
    ))).isSameAs(failure);

    ExternalIdentityLinkFlow.PendingLink pending = flow.pending(request.getSession());
    assertThat(pending.subject()).isEqualTo("subject-1");
    assertThat(pending.email()).isEqualTo("member@example.com");
    assertThat(pending.explicit()).isFalse();
  }

  @Test
  void localLoginCompletesPendingExternalBinding() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    OAuth2AuthenticationException failure = new OAuth2AuthenticationException(
        new OAuth2Error("external_account_not_linked")
    );
    Map<String, Object> attributes = Map.of(
        "sub", "subject-1", "email", "member@example.com"
    );
    when(accountLinker.link(provider(), attributes)).thenThrow(failure);
    assertThatThrownBy(() -> flow.resolve(request, provider(), attributes))
        .isSameAs(failure);
    when(providerService.resolve("example")).thenReturn(provider());
    User user = user("user-1");
    when(accountLinker.linkToUser(
        provider(), "subject-1", "member@example.com", "user-1"
    )).thenReturn(user);

    assertThat(flow.completeAfterLocalLogin(request.getSession(), user)).isTrue();

    verify(accountLinker).linkToUser(
        provider(), "subject-1", "member@example.com", "user-1"
    );
    assertThat(flow.pending(request.getSession())).isNull();
  }

  private ResolvedExternalIdentityProvider provider() {
    return new ResolvedExternalIdentityProvider(
        "provider-1", "example", "Example", ExternalIdentityProviderPreset.CUSTOM,
        ExternalIdentityProviderProtocol.OIDC, "https://issuer.example.com",
        null, null, null, null, "client", "secret", "client_secret_basic",
        Set.of("openid", "profile", "email"), "sub", "sub", "email",
        "email_verified", ExternalIdentityMatchStrategy.VERIFIED_EMAIL, true,
        false, Instant.parse("2026-01-01T00:00:00Z")
    );
  }

  private User user(final String id) {
    User user = new User();
    user.setId(id);
    user.setEnabled(true);
    user.setAccountNonLocked(true);
    user.setAccountNonExpired(true);
    user.setCredentialsNonExpired(true);
    return user;
  }
}
