/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.oidc.service.initialize;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;


/**
 * Initializes OAuth2 client registrations on application startup.
 *
 * <p>This component automatically registers OAuth2 clients defined in
 * {@link Oauth2ClientInitializeProperties}. If a client is not already registered,
 * it will be saved to the {@link RegisteredClientRepository}.
 * </p>
 */
@Slf4j
@Component
public class ClientRegistrationInitialize implements ApplicationRunner {

  /**
   * List of pre-configured OAuth2 clients from application properties.
   */
  private final Oauth2ClientInitializeProperties clientProperties;

  /**
   * Repository for storing registered OAuth2 clients.
   */
  private final RegisteredClientRepository registeredClientRepository;

  /**
   * Constructs an instance of {@code ClientRegistrationInitialize}.
   *
   * @param clientProperties           the authorization server properties defining OAuth2 clients
   * @param registeredClientRepository the repository storing registered clients
   * @throws IllegalArgumentException if any parameter is {@code null}
   */
  public ClientRegistrationInitialize(
      Oauth2ClientInitializeProperties clientProperties,
      RegisteredClientRepository registeredClientRepository) {
    this.clientProperties = clientProperties;
    this.registeredClientRepository = registeredClientRepository;
  }

  /**
   * Runs the client registration process on application startup.
   *
   * <p>Iterates through configured clients, checks if they are already registered,
   * and registers missing clients while encoding their secrets.
   * </p>
   *
   * @param args the application startup arguments
   */
  @Override
  @Transactional
  @SuppressWarnings("null")
  public void run(ApplicationArguments args) {
    clientProperties.getRegistration().forEach((k, registration) -> {
      var provider = clientProperties.getProvider().get(registration.getProvider());
      var registeredClient = buildRegisteredClient(registration, provider);
      log.info("Checking client registration for {}", registeredClient.getClientId());

      if (registeredClientRepository.findByClientId(registeredClient.getClientId()) == null) {
        log.info("Found client with id {}", registeredClient.getClientId());

        // Encode client secret before saving
        registeredClientRepository.save(registeredClient);

        log.info("Saved registered client {}", registeredClient.getClientId());
      }
    });
  }

  /**
   * Builds a {@link RegisteredClient} from the given registration and provider properties.
   *
   * @param registration the client registration properties
   * @param provider     the client provider properties
   * @return a {@link RegisteredClient}
   */
  protected RegisteredClient buildRegisteredClient(
      Oauth2ClientInitializeProperties.Registration registration,
      Oauth2ClientInitializeProperties.Provider provider) {
    RegisteredClient.Builder builder = RegisteredClient
        .withId(registration.getClientId())
        .clientId(registration.getClientId())
        .clientSecret(registration.getClientSecret())
        .clientName(registration.getClientName());

    if (registration.getClientAuthenticationMethod() != null) {
      builder.clientAuthenticationMethod(
          new ClientAuthenticationMethod(registration.getClientAuthenticationMethod()));
    }

    if (registration.getAuthorizationGrantType() != null) {
      switch (registration.getAuthorizationGrantType()) {
        case "authorization_code" -> {
          builder.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE);
          builder.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
        }
        case "client_credentials" -> builder.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS);
        default -> builder.authorizationGrantType(new AuthorizationGrantType(registration.getAuthorizationGrantType()));
      }
      builder.authorizationGrantType(
          new AuthorizationGrantType(registration.getAuthorizationGrantType()));
    }

    if (registration.getRedirectUri() != null) {
      builder.redirectUri(registration.getRedirectUri());
    }

    if (registration.getScope() != null) {
      builder.scopes(scopes -> scopes.addAll(registration.getScope()));
    }

    if (provider != null) {
      ClientSettings.Builder clientSettingsBuilder = ClientSettings.builder()
          .requireProofKey(true)
          .tokenEndpointAuthenticationSigningAlgorithm(SignatureAlgorithm.RS256)
          .requireAuthorizationConsent(true);
      if (provider.getJwkSetUri() != null) {
        clientSettingsBuilder.jwkSetUrl(provider.getJwkSetUri());
      }
      builder.clientSettings(clientSettingsBuilder.build());
      builder.tokenSettings(TokenSettings.builder()
          // 是否复用 Refresh Token
          .reuseRefreshTokens(true)
          // 是否启用 X.509 证书绑定的 Access Token
          .x509CertificateBoundAccessTokens(false)
          // ID Token 签名算法
          .idTokenSignatureAlgorithm(SignatureAlgorithm.RS256)
          // Access Token 有效期 (300 秒 = 5 分钟)
          .accessTokenTimeToLive(Duration.ofSeconds(300))
          // Access Token 格式 (self-contained = JWT)
          .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
          // Refresh Token 有效期 (3600 秒 = 1 小时)
          .refreshTokenTimeToLive(Duration.ofSeconds(3600))
          // Authorization Code 有效期 (300 秒 = 5 分钟)
          .authorizationCodeTimeToLive(Duration.ofSeconds(300))
          // Device Code 有效期 (300 秒 = 5 分钟)
          .deviceCodeTimeToLive(Duration.ofSeconds(300))
          .build());
    }
    return builder.build();
  }
}