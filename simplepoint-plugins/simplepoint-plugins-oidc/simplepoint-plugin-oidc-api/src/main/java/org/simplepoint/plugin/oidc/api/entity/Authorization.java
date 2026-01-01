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
 * Entity representing an OAuth2 authorization record.
 *
 * <p>This class stores OAuth2 authorization details such as access tokens, refresh tokens,
 * authorization codes, and other relevant metadata. The data is stored in the database
 * for authentication and authorization purposes.
 * </p>
 */
@Data
@Entity
@Table(name = "auth_oauth2_authorization")
public class Authorization {

  /**
   * Unique identifier for the authorization record.
   */
  @Id
  @Column
  private String id;

  /**
   * Identifier for the registered OAuth2 client associated with this authorization.
   */
  private String registeredClientId;

  /**
   * The principal (user) associated with this authorization.
   */
  private String principalName;

  /**
   * The OAuth2 grant type used for authorization (e.g., authorization_code, client_credentials).
   */
  private String authorizationGrantType;

  /**
   * The authorized scopes granted to the client.
   */
  @Column(length = 1000, columnDefinition = "text")
  private String authorizedScopes;

  /**
   * Additional attributes related to the authorization.
   */
  @Column(length = 4000, columnDefinition = "text")
  private String attributes;

  /**
   * The state parameter used during the authorization request.
   */
  @Column(length = 500)
  private String state;

  // Authorization Code Details

  /**
   * The authorization code issued for the client.
   */
  @Column(length = 4000, columnDefinition = "text")
  private String authorizationCodeValue;

  /**
   * Timestamp indicating when the authorization code was issued.
   */
  private Instant authorizationCodeIssuedAt;

  /**
   * Timestamp indicating when the authorization code expires.
   */
  private Instant authorizationCodeExpiresAt;

  /**
   * Metadata related to the authorization code.
   */
  private String authorizationCodeMetadata;

  // Access Token Details

  /**
   * The access token issued for authorization.
   */
  @Column(length = 4000, columnDefinition = "text")
  private String accessTokenValue;

  /**
   * Timestamp indicating when the access token was issued.
   */
  private Instant accessTokenIssuedAt;

  /**
   * Timestamp indicating when the access token expires.
   */
  private Instant accessTokenExpiresAt;

  /**
   * Metadata related to the access token.
   */
  @Column(length = 2000)
  private String accessTokenMetadata;

  /**
   * The type of access token (e.g., Bearer).
   */
  private String accessTokenType;

  /**
   * The scopes granted to the access token.
   */
  @Column(length = 1000)
  private String accessTokenScopes;

  // Refresh Token Details

  /**
   * The refresh token associated with this authorization.
   */
  @Column(length = 4000, columnDefinition = "text")
  private String refreshTokenValue;

  /**
   * Timestamp indicating when the refresh token was issued.
   */
  private Instant refreshTokenIssuedAt;

  /**
   * Timestamp indicating when the refresh token expires.
   */
  private Instant refreshTokenExpiresAt;

  /**
   * Metadata related to the refresh token.
   */
  @Column(length = 2000)
  private String refreshTokenMetadata;

  // OpenID Connect ID Token Details

  /**
   * The OpenID Connect ID token value.
   */
  @Column(length = 4000, columnDefinition = "text")
  private String oidcIdTokenValue;

  /**
   * Timestamp indicating when the ID token was issued.
   */
  private Instant oidcIdTokenIssuedAt;

  /**
   * Timestamp indicating when the ID token expires.
   */
  private Instant oidcIdTokenExpiresAt;

  /**
   * Metadata related to the ID token.
   */
  @Column(length = 2000)
  private String oidcIdTokenMetadata;

  /**
   * The claims associated with the OpenID Connect ID token.
   */
  @Column(length = 2000)
  private String oidcIdTokenClaims;

  // User Code Details

  /**
   * The user code used for device authorization.
   */
  @Column(length = 4000, columnDefinition = "text")
  private String userCodeValue;

  /**
   * Timestamp indicating when the user code was issued.
   */
  private Instant userCodeIssuedAt;

  /**
   * Timestamp indicating when the user code expires.
   */
  private Instant userCodeExpiresAt;

  /**
   * Metadata related to the user code.
   */
  @Column(length = 2000)
  private String userCodeMetadata;

  // Device Code Details

  /**
   * The device code used for device authorization.
   */
  @Column(length = 4000, columnDefinition = "text")
  private String deviceCodeValue;

  /**
   * Timestamp indicating when the device code was issued.
   */
  private Instant deviceCodeIssuedAt;

  /**
   * Timestamp indicating when the device code expires.
   */
  private Instant deviceCodeExpiresAt;

  /**
   * Metadata related to the device code.
   */
  @Column(length = 2000)
  private String deviceCodeMetadata;
}