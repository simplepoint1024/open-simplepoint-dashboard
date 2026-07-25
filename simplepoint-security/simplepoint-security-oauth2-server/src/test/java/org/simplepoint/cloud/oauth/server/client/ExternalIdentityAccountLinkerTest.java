package org.simplepoint.cloud.oauth.server.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.oidc.api.entity.ExternalIdentityLink;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityMatchStrategy;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityProviderPreset;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityProviderProtocol;
import org.simplepoint.plugin.oidc.api.model.ResolvedExternalIdentityProvider;
import org.simplepoint.plugin.oidc.api.repository.ExternalIdentityLinkRepository;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.security.entity.User;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

@ExtendWith(MockitoExtension.class)
class ExternalIdentityAccountLinkerTest {

  @Mock
  private ExternalIdentityLinkRepository linkRepository;

  @Mock
  private UsersService usersService;

  private ExternalIdentityAccountLinker linker;

  @BeforeEach
  void setUp() {
    linker = new ExternalIdentityAccountLinker(linkRepository, usersService);
  }

  @Test
  void reusesDurableSubjectBindingWithoutEmailRematch() {
    ExternalIdentityLink link = new ExternalIdentityLink();
    link.setProviderId("provider-1");
    link.setExternalSubject("subject-1");
    link.setUserId("user-1");
    User user = usableUser("user-1", "member@example.com");
    when(linkRepository.findActiveByProviderAndSubject("provider-1", "subject-1"))
        .thenReturn(Optional.of(link));
    when(usersService.findByIdForAuthorization("user-1")).thenReturn(Optional.of(user));
    when(linkRepository.save(link)).thenReturn(link);

    User result = linker.link(provider(true), Map.of(
        "sub", "subject-1",
        "email", "changed@example.com",
        "email_verified", true
    ));

    assertThat(result).isSameAs(user);
    assertThat(link.getLastLoginAt()).isNotNull();
    verify(usersService, never()).loadUserByUsername(any());
  }

  @Test
  void createsBindingOnlyForVerifiedExistingLocalEmail() {
    User user = usableUser("user-1", "member@example.com");
    when(linkRepository.findActiveByProviderAndSubject("provider-1", "subject-1"))
        .thenReturn(Optional.empty());
    when(usersService.loadUserByUsername("member@example.com")).thenReturn(user);
    when(linkRepository.save(any(ExternalIdentityLink.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    User result = linker.link(provider(true), Map.of(
        "sub", "subject-1",
        "email", "member@example.com",
        "email_verified", true
    ));

    ArgumentCaptor<ExternalIdentityLink> captor =
        ArgumentCaptor.forClass(ExternalIdentityLink.class);
    verify(linkRepository).save(captor.capture());
    assertThat(result).isSameAs(user);
    assertThat(captor.getValue().getProviderId()).isEqualTo("provider-1");
    assertThat(captor.getValue().getExternalSubject()).isEqualTo("subject-1");
    assertThat(captor.getValue().getUserId()).isEqualTo("user-1");
    assertThat(captor.getValue().getEmailAtLink()).isEqualTo("member@example.com");
  }

  @Test
  void rejectsUnverifiedEmailBeforeLookingUpLocalAccount() {
    when(linkRepository.findActiveByProviderAndSubject("provider-1", "subject-1"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> linker.link(provider(true), Map.of(
        "sub", "subject-1",
        "email", "member@example.com",
        "email_verified", false
    ))).isInstanceOf(OAuth2AuthenticationException.class)
        .hasMessageContaining("尚未");

    verify(usersService, never()).loadUserByUsername(any());
    verify(linkRepository, never()).save(any());
  }

  private ResolvedExternalIdentityProvider provider(
      final boolean requireVerifiedEmail
  ) {
    return new ResolvedExternalIdentityProvider(
        "provider-1",
        "example",
        "Example",
        ExternalIdentityProviderPreset.CUSTOM,
        ExternalIdentityProviderProtocol.OIDC,
        "https://issuer.example.com",
        null,
        null,
        null,
        null,
        "client",
        "secret",
        "client_secret_basic",
        Set.of("openid", "profile", "email"),
        "sub",
        "sub",
        "email",
        "email_verified",
        ExternalIdentityMatchStrategy.VERIFIED_EMAIL,
        requireVerifiedEmail,
        false,
        Instant.parse("2026-01-01T00:00:00Z")
    );
  }

  private User usableUser(final String id, final String email) {
    User user = new User();
    user.setId(id);
    user.setEmail(email);
    user.setEnabled(true);
    user.setAccountNonLocked(true);
    user.setAccountNonExpired(true);
    user.setCredentialsNonExpired(true);
    return user;
  }
}
