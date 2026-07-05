package org.simplepoint.plugin.oidc.service.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.oidc.api.entity.Client;
import org.simplepoint.plugin.oidc.api.pojo.dto.OidcClientConfigurationDto;
import org.simplepoint.plugin.oidc.api.repository.OidcClientRepository;
import org.simplepoint.plugin.oidc.service.SecurityJacksonParse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

@ExtendWith(MockitoExtension.class)
class OidcClientServiceImplTest {

  private static final String ENCODED_SECRET = "$2a$10$encodedencodedencodedencodedencodedencodedencodedencodedenc";

  @Mock
  private OidcClientRepository repository;

  @Mock
  private PasswordEncoder passwordEncoder;

  private OidcClientServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new OidcClientServiceImpl(repository, null, passwordEncoder);
  }

  @Test
  void createConfigurationWritesStructuredSettingsAndEncodesSecret() {
    when(passwordEncoder.encode("plain-secret")).thenReturn(ENCODED_SECRET);
    when(repository.save(any(Client.class))).thenAnswer(invocation -> invocation.getArgument(0));

    OidcClientConfigurationDto dto = new OidcClientConfigurationDto();
    dto.setClientId("client-a");
    dto.setClientName("Client A");
    dto.setClientSecret("plain-secret");
    dto.setClientAuthenticationMethods(orderedSet("client_secret_basic"));
    dto.setAuthorizationGrantTypes(orderedSet("authorization_code", "refresh_token"));
    dto.setRedirectUris(orderedSet("http://localhost/callback"));
    dto.setPostLogoutRedirectUris(orderedSet("http://localhost"));
    dto.setScopes(orderedSet("openid", "profile"));
    dto.setRequireAuthorizationConsent(false);
    dto.setRequireProofKey(false);
    dto.setReuseRefreshTokens(false);
    dto.setAccessTokenTtlSeconds(2700L);
    dto.setRefreshTokenTtlSeconds(43200L);
    dto.setAuthorizationCodeTtlSeconds(180L);
    dto.setDeviceCodeTtlSeconds(240L);
    dto.setAccessTokenFormat("reference");
    dto.setIdTokenSignatureAlgorithm("RS256");

    Client saved = service.createConfiguration(dto);

    assertThat(saved.getClientSecret()).isEqualTo(ENCODED_SECRET);
    assertThat(saved.getClientAuthenticationMethods()).isEqualTo("client_secret_basic");
    assertThat(saved.getAuthorizationGrantTypes()).isEqualTo("authorization_code,refresh_token");
    assertThat(saved.getScopes()).isEqualTo("openid,profile");
    ClientSettings clientSettings = ClientSettings.withSettings(
        SecurityJacksonParse.parseMap(saved.getClientSettings())
    ).build();
    TokenSettings tokenSettings = TokenSettings.withSettings(
        SecurityJacksonParse.parseMap(saved.getTokenSettings())
    ).build();
    assertThat(clientSettings.isRequireAuthorizationConsent()).isFalse();
    assertThat(clientSettings.isRequireProofKey()).isFalse();
    assertThat(tokenSettings.isReuseRefreshTokens()).isFalse();
    assertThat(tokenSettings.getAccessTokenFormat()).isEqualTo(OAuth2TokenFormat.REFERENCE);
    assertThat(tokenSettings.getAccessTokenTimeToLive()).isEqualTo(Duration.ofSeconds(2700));
    assertThat(tokenSettings.getRefreshTokenTimeToLive()).isEqualTo(Duration.ofSeconds(43200));
    assertThat(tokenSettings.getAuthorizationCodeTimeToLive()).isEqualTo(Duration.ofSeconds(180));
    assertThat(tokenSettings.getDeviceCodeTimeToLive()).isEqualTo(Duration.ofSeconds(240));
    assertThat(tokenSettings.getIdTokenSignatureAlgorithm()).isEqualTo(SignatureAlgorithm.RS256);
  }

  @Test
  void configurationReadsStoredSettingsIntoDto() {
    Client client = new Client();
    client.setId("db-id");
    client.setClientId("client-a");
    client.setClientIdIssuedAt(Instant.parse("2026-01-01T00:00:00Z"));
    client.setClientName("Client A");
    client.setClientSecret(ENCODED_SECRET);
    client.setClientAuthenticationMethods("client_secret_basic");
    client.setAuthorizationGrantTypes("authorization_code,refresh_token");
    client.setRedirectUris("http://localhost/callback");
    client.setPostLogoutRedirectUris("http://localhost");
    client.setScopes("openid,profile");
    client.setClientSettings(SecurityJacksonParse.writeMap(ClientSettings.builder()
        .requireAuthorizationConsent(false)
        .requireProofKey(false)
        .tokenEndpointAuthenticationSigningAlgorithm(SignatureAlgorithm.RS256)
        .jwkSetUrl("http://localhost/jwks")
        .build()
        .getSettings()));
    client.setTokenSettings(SecurityJacksonParse.writeMap(TokenSettings.builder()
        .reuseRefreshTokens(false)
        .accessTokenFormat(OAuth2TokenFormat.REFERENCE)
        .accessTokenTimeToLive(Duration.ofMinutes(45))
        .refreshTokenTimeToLive(Duration.ofHours(12))
        .authorizationCodeTimeToLive(Duration.ofMinutes(3))
        .deviceCodeTimeToLive(Duration.ofMinutes(4))
        .idTokenSignatureAlgorithm(SignatureAlgorithm.RS256)
        .build()
        .getSettings()));
    when(repository.findById("db-id")).thenReturn(Optional.of(client));

    OidcClientConfigurationDto dto = service.configuration("db-id");

    assertThat(dto.getRequireAuthorizationConsent()).isFalse();
    assertThat(dto.getRequireProofKey()).isFalse();
    assertThat(dto.getJwkSetUrl()).isEqualTo("http://localhost/jwks");
    assertThat(dto.getTokenEndpointAuthenticationSigningAlgorithm()).isEqualTo("RS256");
    assertThat(dto.getAccessTokenFormat()).isEqualTo("reference");
    assertThat(dto.getAccessTokenTtlSeconds()).isEqualTo(2700L);
    assertThat(dto.getRefreshTokenTtlSeconds()).isEqualTo(43200L);
    assertThat(dto.getAuthorizationCodeTtlSeconds()).isEqualTo(180L);
    assertThat(dto.getDeviceCodeTtlSeconds()).isEqualTo(240L);
    assertThat(dto.getScopes()).containsExactly("openid", "profile");
  }

  private LinkedHashSet<String> orderedSet(String... values) {
    return new LinkedHashSet<>(Arrays.asList(values));
  }
}
