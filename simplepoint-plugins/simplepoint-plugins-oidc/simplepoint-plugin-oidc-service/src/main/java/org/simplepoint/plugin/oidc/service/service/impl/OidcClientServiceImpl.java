/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.oidc.service.service.impl;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Pattern;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.oidc.api.entity.Client;
import org.simplepoint.plugin.oidc.api.pojo.dto.OidcClientConfigurationDto;
import org.simplepoint.plugin.oidc.api.repository.OidcClientRepository;
import org.simplepoint.plugin.oidc.api.service.OidcClientService;
import org.simplepoint.plugin.oidc.service.SecurityJacksonParse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.JwsAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.ConfigurationSettingNames;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Implementation that manages OAuth2 clients,
 * providing functionality to store and retrieve registered clients from a repository.
 * This service handles the persistence of OAuth2 client authentication and authorization data.
 * Clients are stored using {@code ClientRepository}, and their configurations are mapped
 * between structured configuration and {@code Client} entity.
 */
@Component
public class OidcClientServiceImpl extends BaseServiceImpl<OidcClientRepository, Client, String> implements OidcClientService {

  private static final Pattern BCRYPT_PATTERN = Pattern.compile("\\A\\$2([ayb])?\\$(\\d\\d)\\$[./0-9A-Za-z]{53}");

  private static final long DEFAULT_ACCESS_TOKEN_SECONDS = 1800L;

  private static final long DEFAULT_REFRESH_TOKEN_SECONDS = 28800L;

  private static final long DEFAULT_CODE_SECONDS = 300L;

  private final PasswordEncoder passwordEncoder;

  /**
   * Constructs a new OidcClientServiceImpl with the specified repository, user context, and details provider service.
   *
   * @param repository             the OidcClientRepository instance for data access
   * @param detailsProviderService the service for providing user details
   */
  public OidcClientServiceImpl(
      OidcClientRepository repository,
      DetailsProviderService detailsProviderService,
      PasswordEncoder passwordEncoder
  ) {
    super(repository, detailsProviderService);
    this.passwordEncoder = passwordEncoder;
  }

  @Override
  public OidcClientConfigurationDto configuration(String id) {
    Client client = getRepository().findById(id)
        .orElseThrow(() -> new NoSuchElementException("OAuth2 client not found: " + id));
    return toConfiguration(client);
  }

  @Override
  public Client createConfiguration(OidcClientConfigurationDto dto) {
    Client client = applyConfiguration(new Client(), dto);
    if (client.getClientIdIssuedAt() == null) {
      client.setClientIdIssuedAt(Instant.now());
    }
    return create(client);
  }

  @Override
  public Client updateConfiguration(String id, OidcClientConfigurationDto dto) {
    Client client = getRepository().findById(id)
        .orElseThrow(() -> new NoSuchElementException("OAuth2 client not found: " + id));
    dto.setId(id);
    return modifyById(applyConfiguration(client, dto));
  }

  private Client applyConfiguration(Client client, OidcClientConfigurationDto dto) {
    client.setId(firstText(dto.getId(), client.getId()));
    client.setClientId(dto.getClientId());
    client.setClientName(dto.getClientName());
    client.setClientSecret(resolveClientSecret(dto.getClientSecret(), client.getClientSecret()));
    client.setClientIdIssuedAt(dto.getClientIdIssuedAt() != null ? dto.getClientIdIssuedAt() : client.getClientIdIssuedAt());
    client.setClientSecretExpiresAt(dto.getClientSecretExpiresAt());
    client.setClientAuthenticationMethods(toCsv(dto.getClientAuthenticationMethods()));
    client.setAuthorizationGrantTypes(toCsv(dto.getAuthorizationGrantTypes()));
    client.setRedirectUris(toCsv(dto.getRedirectUris()));
    client.setPostLogoutRedirectUris(toCsv(dto.getPostLogoutRedirectUris()));
    client.setScopes(toCsv(dto.getScopes()));
    client.setClientSettings(writeClientSettings(dto));
    client.setTokenSettings(writeTokenSettings(dto));
    return client;
  }

  private OidcClientConfigurationDto toConfiguration(Client client) {
    OidcClientConfigurationDto dto = new OidcClientConfigurationDto();
    dto.setId(client.getId());
    dto.setClientId(client.getClientId());
    dto.setClientIdIssuedAt(client.getClientIdIssuedAt());
    dto.setClientName(client.getClientName());
    dto.setClientSecret(client.getClientSecret());
    dto.setClientSecretExpiresAt(client.getClientSecretExpiresAt());
    dto.setClientAuthenticationMethods(toSet(client.getClientAuthenticationMethods()));
    dto.setAuthorizationGrantTypes(toSet(client.getAuthorizationGrantTypes()));
    dto.setRedirectUris(toSet(client.getRedirectUris()));
    dto.setPostLogoutRedirectUris(toSet(client.getPostLogoutRedirectUris()));
    dto.setScopes(toSet(client.getScopes()));
    applyClientSettings(dto, client.getClientSettings());
    applyTokenSettings(dto, client.getTokenSettings());
    return dto;
  }

  private void applyClientSettings(OidcClientConfigurationDto dto, String json) {
    ClientSettings settings = ClientSettings.withSettings(parseSettings(json)).build();
    dto.setRequireProofKey(settings.isRequireProofKey());
    dto.setRequireAuthorizationConsent(settings.isRequireAuthorizationConsent());
    dto.setJwkSetUrl(settings.getJwkSetUrl());
    JwsAlgorithm algorithm = settings.getSetting(
        ConfigurationSettingNames.Client.TOKEN_ENDPOINT_AUTHENTICATION_SIGNING_ALGORITHM
    );
    dto.setTokenEndpointAuthenticationSigningAlgorithm(toAlgorithmValue(algorithm));
  }

  private void applyTokenSettings(OidcClientConfigurationDto dto, String json) {
    TokenSettings settings = TokenSettings.withSettings(parseSettings(json)).build();
    dto.setReuseRefreshTokens(settings.isReuseRefreshTokens());
    dto.setX509CertificateBoundAccessTokens(settings.isX509CertificateBoundAccessTokens());
    dto.setIdTokenSignatureAlgorithm(toAlgorithmValue(settings.getIdTokenSignatureAlgorithm()));
    dto.setAccessTokenFormat(settings.getAccessTokenFormat().getValue());
    dto.setAccessTokenTtlSeconds(toSeconds(settings.getAccessTokenTimeToLive()));
    dto.setRefreshTokenTtlSeconds(toSeconds(settings.getRefreshTokenTimeToLive()));
    dto.setAuthorizationCodeTtlSeconds(toSeconds(settings.getAuthorizationCodeTimeToLive()));
    dto.setDeviceCodeTtlSeconds(toSeconds(settings.getDeviceCodeTimeToLive()));
  }

  private String writeClientSettings(OidcClientConfigurationDto dto) {
    ClientSettings.Builder builder = ClientSettings.builder()
        .requireProofKey(Boolean.TRUE.equals(dto.getRequireProofKey()))
        .requireAuthorizationConsent(Boolean.TRUE.equals(dto.getRequireAuthorizationConsent()));
    SignatureAlgorithm tokenEndpointAlgorithm = parseAlgorithm(dto.getTokenEndpointAuthenticationSigningAlgorithm());
    if (tokenEndpointAlgorithm != null) {
      builder.tokenEndpointAuthenticationSigningAlgorithm(tokenEndpointAlgorithm);
    }
    if (StringUtils.hasText(dto.getJwkSetUrl())) {
      builder.jwkSetUrl(dto.getJwkSetUrl());
    }
    return SecurityJacksonParse.writeMap(builder.build().getSettings());
  }

  private String writeTokenSettings(OidcClientConfigurationDto dto) {
    TokenSettings settings = TokenSettings.builder()
        .reuseRefreshTokens(!Boolean.FALSE.equals(dto.getReuseRefreshTokens()))
        .x509CertificateBoundAccessTokens(Boolean.TRUE.equals(dto.getX509CertificateBoundAccessTokens()))
        .idTokenSignatureAlgorithm(resolveAlgorithm(dto.getIdTokenSignatureAlgorithm()))
        .accessTokenFormat(resolveAccessTokenFormat(dto.getAccessTokenFormat()))
        .accessTokenTimeToLive(toDuration(dto.getAccessTokenTtlSeconds(), DEFAULT_ACCESS_TOKEN_SECONDS))
        .refreshTokenTimeToLive(toDuration(dto.getRefreshTokenTtlSeconds(), DEFAULT_REFRESH_TOKEN_SECONDS))
        .authorizationCodeTimeToLive(toDuration(dto.getAuthorizationCodeTtlSeconds(), DEFAULT_CODE_SECONDS))
        .deviceCodeTimeToLive(toDuration(dto.getDeviceCodeTtlSeconds(), DEFAULT_CODE_SECONDS))
        .build();
    return SecurityJacksonParse.writeMap(settings.getSettings());
  }

  private Map<String, Object> parseSettings(String json) {
    if (!StringUtils.hasText(json)) {
      return Map.of();
    }
    return SecurityJacksonParse.parseMap(json);
  }

  private Set<String> toSet(String csv) {
    LinkedHashSet<String> values = new LinkedHashSet<>();
    if (!StringUtils.hasText(csv)) {
      return values;
    }
    StringUtils.commaDelimitedListToSet(csv).stream()
        .map(String::trim)
        .filter(StringUtils::hasText)
        .forEach(values::add);
    return values;
  }

  private String toCsv(Set<String> values) {
    if (values == null || values.isEmpty()) {
      return "";
    }
    return StringUtils.collectionToCommaDelimitedString(values.stream()
        .map(String::trim)
        .filter(StringUtils::hasText)
        .toList());
  }

  private String firstText(String preferred, String fallback) {
    return StringUtils.hasText(preferred) ? preferred : fallback;
  }

  private String resolveClientSecret(String submittedSecret, String currentSecret) {
    if (!StringUtils.hasText(submittedSecret)) {
      return currentSecret;
    }
    String value = submittedSecret.trim();
    return BCRYPT_PATTERN.matcher(value).matches() ? value : passwordEncoder.encode(value);
  }

  private Long toSeconds(Duration duration) {
    return duration == null ? null : duration.toSeconds();
  }

  private Duration toDuration(Long seconds, long fallbackSeconds) {
    long resolved = seconds == null || seconds <= 0 ? fallbackSeconds : seconds;
    return Duration.ofSeconds(resolved);
  }

  private OAuth2TokenFormat resolveAccessTokenFormat(String value) {
    if ("reference".equalsIgnoreCase(value)) {
      return OAuth2TokenFormat.REFERENCE;
    }
    return OAuth2TokenFormat.SELF_CONTAINED;
  }

  private SignatureAlgorithm resolveAlgorithm(String value) {
    SignatureAlgorithm algorithm = parseAlgorithm(value);
    return algorithm == null ? SignatureAlgorithm.PS256 : algorithm;
  }

  private SignatureAlgorithm parseAlgorithm(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return switch (value.trim().toUpperCase()) {
      case "RS256" -> SignatureAlgorithm.RS256;
      case "ES256" -> SignatureAlgorithm.ES256;
      case "PS256" -> SignatureAlgorithm.PS256;
      default -> throw new IllegalArgumentException("Unsupported signature algorithm: " + value);
    };
  }

  private String toAlgorithmValue(JwsAlgorithm algorithm) {
    return algorithm == null ? null : algorithm.getName();
  }
}
