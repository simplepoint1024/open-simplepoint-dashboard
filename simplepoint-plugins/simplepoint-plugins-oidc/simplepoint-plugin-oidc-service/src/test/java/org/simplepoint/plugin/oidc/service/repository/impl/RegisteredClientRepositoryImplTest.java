package org.simplepoint.plugin.oidc.service.repository.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.oidc.api.entity.Client;
import org.simplepoint.plugin.oidc.service.SecurityJacksonParse;
import org.simplepoint.plugin.oidc.service.repository.JpaClientRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RegisteredClientRepositoryImplTest {

  @Mock
  JpaClientRepository clientRepository;

  @Mock
  PasswordEncoder passwordEncoder;

  // ── constructor ───────────────────────────────────────────────────────────

  @Test
  void constructor_nullRepository_throwsIllegalArgument() {
    assertThatThrownBy(() -> new RegisteredClientRepositoryImpl(null, passwordEncoder))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void constructor_validArgs_succeeds() {
    new RegisteredClientRepositoryImpl(clientRepository, passwordEncoder);
  }

  // ── save ─────────────────────────────────────────────────────────────────

  @Test
  void save_nullClient_throwsIllegalArgument() {
    RegisteredClientRepositoryImpl repo = new RegisteredClientRepositoryImpl(clientRepository, passwordEncoder);
    assertThatThrownBy(() -> repo.save(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void save_plainSecret_encodesPassword() {
    RegisteredClientRepositoryImpl repo = new RegisteredClientRepositoryImpl(clientRepository, passwordEncoder);
    Map<String, Object> clientSettingsMap = ClientSettings.builder().build().getSettings();
    Map<String, Object> tokenSettingsMap = TokenSettings.builder().build().getSettings();

    try (MockedStatic<SecurityJacksonParse> sjp = mockStatic(SecurityJacksonParse.class)) {
      sjp.when(() -> SecurityJacksonParse.writeMap(any())).thenReturn("{}");
      when(passwordEncoder.encode("mysecret")).thenReturn("$encoded$");

      RegisteredClient rc = RegisteredClient.withId("id1")
          .clientId("client1")
          .clientSecret("mysecret")
          .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
          .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
          .redirectUri("http://localhost/callback")
          .build();

      repo.save(rc);

      verify(passwordEncoder).encode("mysecret");
      verify(clientRepository).save(any(Client.class));
    }
  }

  @Test
  void save_bcryptSecret_keepsAsIs() {
    RegisteredClientRepositoryImpl repo = new RegisteredClientRepositoryImpl(clientRepository, passwordEncoder);

    try (MockedStatic<SecurityJacksonParse> sjp = mockStatic(SecurityJacksonParse.class)) {
      sjp.when(() -> SecurityJacksonParse.writeMap(any())).thenReturn("{}");

      String bcrypt = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
      RegisteredClient rc = RegisteredClient.withId("id1")
          .clientId("client1")
          .clientSecret(bcrypt)
          .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
          .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
          .redirectUri("http://localhost/callback")
          .build();

      repo.save(rc);

      verify(passwordEncoder, never()).encode(anyString());
      verify(clientRepository).save(argThat(c -> bcrypt.equals(c.getClientSecret())));
    }
  }

  @Test
  void save_nullSecret_doesNotEncode() {
    RegisteredClientRepositoryImpl repo = new RegisteredClientRepositoryImpl(clientRepository, passwordEncoder);

    try (MockedStatic<SecurityJacksonParse> sjp = mockStatic(SecurityJacksonParse.class)) {
      sjp.when(() -> SecurityJacksonParse.writeMap(any())).thenReturn("{}");

      RegisteredClient rc = RegisteredClient.withId("id1")
          .clientId("client1")
          .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
          .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
          .redirectUri("http://localhost/callback")
          .build();

      repo.save(rc);

      verify(passwordEncoder, never()).encode(anyString());
    }
  }

  // ── findById ─────────────────────────────────────────────────────────────

  @Test
  void findById_emptyId_throwsIllegalArgument() {
    RegisteredClientRepositoryImpl repo = new RegisteredClientRepositoryImpl(clientRepository, passwordEncoder);
    assertThatThrownBy(() -> repo.findById(""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void findById_notFound_returnsNull() {
    RegisteredClientRepositoryImpl repo = new RegisteredClientRepositoryImpl(clientRepository, passwordEncoder);
    when(clientRepository.findById("missing")).thenReturn(Optional.empty());

    assertThat(repo.findById("missing")).isNull();
  }

  @Test
  void findById_found_convertsToRegisteredClient() {
    RegisteredClientRepositoryImpl repo = new RegisteredClientRepositoryImpl(clientRepository, passwordEncoder);
    Map<String, Object> clientSettingsMap = ClientSettings.builder().build().getSettings();
    Map<String, Object> tokenSettingsMap = TokenSettings.builder().build().getSettings();

    Client client = clientEntity("id1", "myClientId");

    try (MockedStatic<SecurityJacksonParse> sjp = mockStatic(SecurityJacksonParse.class)) {
      sjp.when(() -> SecurityJacksonParse.parseMap("client-settings")).thenReturn(clientSettingsMap);
      sjp.when(() -> SecurityJacksonParse.parseMap("token-settings")).thenReturn(tokenSettingsMap);

      when(clientRepository.findById("id1")).thenReturn(Optional.of(client));

      RegisteredClient result = repo.findById("id1");

      assertThat(result).isNotNull();
      assertThat(result.getClientId()).isEqualTo("myClientId");
    }
  }

  // ── findByClientId ────────────────────────────────────────────────────────

  @Test
  void findByClientId_emptyId_throwsIllegalArgument() {
    RegisteredClientRepositoryImpl repo = new RegisteredClientRepositoryImpl(clientRepository, passwordEncoder);
    assertThatThrownBy(() -> repo.findByClientId(""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void findByClientId_notFound_returnsNull() {
    RegisteredClientRepositoryImpl repo = new RegisteredClientRepositoryImpl(clientRepository, passwordEncoder);
    when(clientRepository.findByClientId("unknown")).thenReturn(Optional.empty());

    assertThat(repo.findByClientId("unknown")).isNull();
  }

  @Test
  void findByClientId_found_convertsToRegisteredClient() {
    RegisteredClientRepositoryImpl repo = new RegisteredClientRepositoryImpl(clientRepository, passwordEncoder);
    Map<String, Object> clientSettingsMap = ClientSettings.builder().build().getSettings();
    Map<String, Object> tokenSettingsMap = TokenSettings.builder().build().getSettings();

    Client client = clientEntity("id2", "app-client");

    try (MockedStatic<SecurityJacksonParse> sjp = mockStatic(SecurityJacksonParse.class)) {
      sjp.when(() -> SecurityJacksonParse.parseMap("client-settings")).thenReturn(clientSettingsMap);
      sjp.when(() -> SecurityJacksonParse.parseMap("token-settings")).thenReturn(tokenSettingsMap);

      when(clientRepository.findByClientId("app-client")).thenReturn(Optional.of(client));

      RegisteredClient result = repo.findByClientId("app-client");

      assertThat(result).isNotNull();
      assertThat(result.getClientId()).isEqualTo("app-client");
    }
  }

  // ── toObject: grant/auth method resolution ────────────────────────────────

  @Test
  void findById_authorizationCodeGrantType_resolvedCorrectly() {
    RegisteredClientRepositoryImpl repo = new RegisteredClientRepositoryImpl(clientRepository, passwordEncoder);
    Map<String, Object> clientSettingsMap = ClientSettings.builder().build().getSettings();
    Map<String, Object> tokenSettingsMap = TokenSettings.builder().build().getSettings();

    Client client = clientEntity("id3", "code-client");
    client.setAuthorizationGrantTypes("authorization_code");
    client.setClientAuthenticationMethods("client_secret_basic");

    try (MockedStatic<SecurityJacksonParse> sjp = mockStatic(SecurityJacksonParse.class)) {
      sjp.when(() -> SecurityJacksonParse.parseMap("client-settings")).thenReturn(clientSettingsMap);
      sjp.when(() -> SecurityJacksonParse.parseMap("token-settings")).thenReturn(tokenSettingsMap);

      when(clientRepository.findById("id3")).thenReturn(Optional.of(client));

      RegisteredClient result = repo.findById("id3");

      assertThat(result.getAuthorizationGrantTypes())
          .contains(AuthorizationGrantType.AUTHORIZATION_CODE);
      assertThat(result.getClientAuthenticationMethods())
          .contains(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static Client clientEntity(String id, String clientId) {
    Client client = new Client();
    client.setId(id);
    client.setClientId(clientId);
    client.setClientName(clientId + "-name");
    client.setClientSecret("$2a$10$secret");
    client.setClientAuthenticationMethods("none");
    client.setAuthorizationGrantTypes("authorization_code");
    client.setRedirectUris("http://localhost/callback");
    client.setPostLogoutRedirectUris("");
    client.setScopes("openid");
    client.setClientSettings("client-settings");
    client.setTokenSettings("token-settings");
    return client;
  }
}
