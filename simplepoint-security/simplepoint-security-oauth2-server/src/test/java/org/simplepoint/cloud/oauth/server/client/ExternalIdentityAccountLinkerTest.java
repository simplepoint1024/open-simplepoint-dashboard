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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.oidc.api.error.ExternalIdentityBindingException;
import org.simplepoint.plugin.oidc.api.error.ExternalIdentityBindingException.Reason;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityBinding;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityMatchStrategy;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityProviderPreset;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityProviderProtocol;
import org.simplepoint.plugin.oidc.api.model.ResolvedExternalIdentityProvider;
import org.simplepoint.plugin.oidc.api.service.ExternalIdentityAccountService;
import org.simplepoint.plugin.rbac.core.api.service.UserManagementService;
import org.simplepoint.security.entity.User;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

@ExtendWith(MockitoExtension.class)
class ExternalIdentityAccountLinkerTest {
  @Mock private ExternalIdentityAccountService accounts;
  @Mock private UserManagementService usersService;
  private ExternalIdentityAccountLinker linker;
  private final ExternalIdentityBinding binding =
      new ExternalIdentityBinding("binding", "provider-1", "subject-1", "user-1");

  @BeforeEach
  void setUp() {
    linker = new ExternalIdentityAccountLinker(accounts, usersService);
  }

  @Test
  void reusesDurableSubjectBindingWithoutEmailRematch() {
    var user = usableUser("user-1", "member@example.com");
    when(accounts.find("provider-1", "subject-1")).thenReturn(Optional.of(binding));
    when(usersService.findByIdForAuthorization("user-1")).thenReturn(Optional.of(user));
    assertThat(linker.link(provider(true), attributes(true))).isSameAs(user);
    verify(accounts).recordLogin(binding);
    verify(usersService, never()).loadUserByUsername(any());
  }

  @Test
  void createsBindingOnlyForVerifiedExistingLocalEmailAndRechecksAccount() {
    var user = usableUser("user-1", "member@example.com");
    when(usersService.loadUserByUsername("member@example.com")).thenReturn(user);
    when(usersService.findByIdForAuthorization("user-1")).thenReturn(Optional.of(user));
    when(accounts.bind("provider-1", "subject-1", "member@example.com", "user-1"))
        .thenReturn(binding);
    assertThat(linker.link(provider(true), attributes(true))).isSameAs(user);
    verify(accounts).recordLogin(binding);
  }

  @Test
  void rejectsUnverifiedEmailBeforeLookingUpLocalAccount() {
    assertThatThrownBy(() -> linker.link(provider(true), attributes(false)))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .hasMessageContaining("尚未");
    verify(usersService, never()).loadUserByUsername(any());
    verify(accounts, never()).bind(any(), any(), any(), any());
  }

  @Test
  void explicitlyBindsAfterLocalAuthentication() {
    var user = usableUser("user-1", "member@example.com");
    when(usersService.findByIdForAuthorization("user-1")).thenReturn(Optional.of(user));
    when(accounts.bind("provider-1", "subject-1", "private@example.com", "user-1"))
        .thenReturn(binding);
    assertThat(linker.linkToUser(provider(true), "subject-1", "private@example.com", "user-1"))
        .isSameAs(user);
    verify(accounts).recordLogin(binding);
  }

  @Test
  void conflictsNeverSwitchTheAuthenticatedTargetAccount() {
    var user = usableUser("user-1", "member@example.com");
    when(usersService.findByIdForAuthorization("user-1")).thenReturn(Optional.of(user));
    when(accounts.bind("provider-1", "subject-1", "member@example.com", "user-1"))
        .thenThrow(new ExternalIdentityBindingException(Reason.SUBJECT_IN_USE));
    assertThatThrownBy(
            () -> linker.linkToUser(provider(true), "subject-1", "member@example.com", "user-1"))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .hasMessageContaining("其他平台账号");
    verify(accounts, never()).recordLogin(any());
  }

  @Test
  void disabledBoundAccountCannotUpdateLoginMetadata() {
    var user = usableUser("user-1", "member@example.com");
    user.setEnabled(false);
    when(accounts.find("provider-1", "subject-1")).thenReturn(Optional.of(binding));
    when(usersService.findByIdForAuthorization("user-1")).thenReturn(Optional.of(user));
    assertThatThrownBy(() -> linker.link(provider(true), attributes(true)))
        .isInstanceOf(OAuth2AuthenticationException.class);
    verify(accounts, never()).recordLogin(any());
  }

  @Test
  void deletedAccountCannotBeLinkedFromAnOldSession() {
    var user = usableUser("user-1", "member@example.com");
    user.setDeletedAt(Instant.now());
    when(usersService.findByIdForAuthorization("user-1")).thenReturn(Optional.of(user));
    assertThatThrownBy(() -> linker.linkToUser(provider(true), "subject-1", null, "user-1"))
        .isInstanceOf(OAuth2AuthenticationException.class);
    verify(accounts, never()).bind(any(), any(), any(), any());
  }

  @Test
  void accountDisabledDuringBindingCannotCompleteLogin() {
    var user = usableUser("user-1", "member@example.com");
    var disabled = usableUser("user-1", "member@example.com");
    disabled.setEnabled(false);
    when(usersService.findByIdForAuthorization("user-1"))
        .thenReturn(Optional.of(user))
        .thenReturn(Optional.of(disabled));
    when(accounts.bind("provider-1", "subject-1", null, "user-1")).thenReturn(binding);
    assertThatThrownBy(() -> linker.linkToUser(provider(true), "subject-1", null, "user-1"))
        .isInstanceOf(OAuth2AuthenticationException.class);
    verify(accounts, never()).recordLogin(any());
  }

  private Map<String, Object> attributes(boolean verified) {
    return Map.of("sub", "subject-1", "email", "member@example.com", "email_verified", verified);
  }

  private ResolvedExternalIdentityProvider provider(final boolean requireVerifiedEmail) {
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
        Instant.parse("2026-01-01T00:00:00Z"));
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
