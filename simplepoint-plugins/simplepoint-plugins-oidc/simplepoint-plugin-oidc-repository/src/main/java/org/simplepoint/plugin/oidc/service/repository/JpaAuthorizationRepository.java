/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.oidc.service.repository;

import java.util.Optional;
import org.simplepoint.plugin.oidc.api.entity.Authorization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository interface for managing OAuth2 authorization entities.
 *
 * <p>This repository provides methods to store, retrieve, and delete OAuth2 authorization
 * records based on various token types such as state, authorization code, access token,
 * refresh token, OIDC ID token, user code, and device code.
 * </p>
 */
@Repository
public interface JpaAuthorizationRepository extends JpaRepository<Authorization, String> {

  /**
   * Finds an authorization by state.
   *
   * @param state the OAuth2 state parameter
   * @return an {@link Optional} containing the found {@link Authorization}, or empty if not found
   */
  Optional<Authorization> findByState(String state);

  /**
   * Finds an authorization by authorization code value.
   *
   * @param authorizationCode the authorization code
   * @return an {@link Optional} containing the found {@link Authorization}, or empty if not found
   */
  Optional<Authorization> findByAuthorizationCodeValue(String authorizationCode);

  /**
   * Finds an authorization by access token value.
   *
   * @param accessToken the access token
   * @return an {@link Optional} containing the found {@link Authorization}, or empty if not found
   */
  Optional<Authorization> findByAccessTokenValue(String accessToken);

  /**
   * Finds an authorization by refresh token value.
   *
   * @param refreshToken the refresh token
   * @return an {@link Optional} containing the found {@link Authorization}, or empty if not found
   */
  Optional<Authorization> findByRefreshTokenValue(String refreshToken);

  /**
   * Finds an authorization by OpenID Connect (OIDC) ID token value.
   *
   * @param idToken the OIDC ID token
   * @return an {@link Optional} containing the found {@link Authorization}, or empty if not found
   */
  Optional<Authorization> findByOidcIdTokenValue(String idToken);

  /**
   * Finds an authorization by user code value.
   *
   * @param userCode the user code for OAuth2 device flow
   * @return an {@link Optional} containing the found {@link Authorization}, or empty if not found
   */
  Optional<Authorization> findByUserCodeValue(String userCode);

  /**
   * Finds an authorization by device code value.
   *
   * @param deviceCode the device code for OAuth2 device flow
   * @return an {@link Optional} containing the found {@link Authorization}, or empty if not found
   */
  Optional<Authorization> findByDeviceCodeValue(String deviceCode);

  /**
   * Finds an authorization by any token type (state, authorization code, access token, refresh token,
   * OIDC ID token, user code, or device code).
   *
   * @param token the token used for searching
   * @return an {@link Optional} containing the found {@link Authorization}, or empty if not found
   */
  @Query("select a from Authorization a where a.state = :token"
      + " or a.authorizationCodeValue = :token"
      + " or a.accessTokenValue = :token"
      + " or a.refreshTokenValue = :token"
      + " or a.oidcIdTokenValue = :token"
      + " or a.userCodeValue = :token"
      + " or a.deviceCodeValue = :token"
  )
  Optional<Authorization> findByStateOrAuthorizationCodeValueOrAccessTokenValueOrRefreshTokenValueOrOidcIdTokenValueOrUserCodeValueOrDeviceCodeValue(
      @Param("token") String token);
}