/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.oidc.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Data;


/**
 * Entity representing an OAuth2 client.
 *
 * <p>This class stores OAuth2 client configuration details, including authentication methods,
 * authorization grant types, and various token settings. The data is persisted for managing
 * registered clients in an authorization server.
 * </p>
 */
@Data
@Entity
@Table(name = "security_oauth2_client")
public class Client {

  /**
   * Unique identifier for the client entity.
   */
  @Id
  private String id;

  /**
   * The client identifier used in OAuth2 authentication flows.
   */
  private String clientId;

  /**
   * Timestamp indicating when the client ID was issued.
   */
  private Instant clientIdIssuedAt;

  /**
   * The client secret used for authentication in confidential OAuth2 flows.
   */
  private String clientSecret;

  /**
   * Timestamp indicating when the client secret expires.
   */
  private Instant clientSecretExpiresAt;

  /**
   * Human-readable name assigned to the client.
   */
  private String clientName;

  /**
   * Authentication methods supported by the client (e.g., client_secret_basic, client_secret_post).
   */
  @Column(length = 1000)
  private String clientAuthenticationMethods;

  /**
   * Supported OAuth2 authorization grant types (e.g., authorization_code, client_credentials).
   */
  @Column(length = 1000)
  private String authorizationGrantTypes;

  /**
   * Allowed redirect URIs for authorization responses.
   */
  @Column(length = 1000)
  private String redirectUris;

  /**
   * Allowed post-logout redirect URIs for OpenID Connect logout flows.
   */
  @Column(length = 1000)
  private String postLogoutRedirectUris;

  /**
   * Scopes granted to the client (e.g., read, write, profile).
   */
  @Column(length = 1000)
  private String scopes;

  /**
   * JSON-encoded client settings (e.g., requiring consent, token endpoint authentication).
   */
  @Column(length = 2000)
  private String clientSettings;

  /**
   * JSON-encoded token settings (e.g., access token lifetime, refresh token policies).
   */
  @Column(length = 2000)
  private String tokenSettings;
}