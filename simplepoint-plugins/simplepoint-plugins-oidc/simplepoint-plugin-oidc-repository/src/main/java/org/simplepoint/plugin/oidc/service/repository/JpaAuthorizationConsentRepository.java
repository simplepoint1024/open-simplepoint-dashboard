/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.oidc.service.repository;

import java.util.Optional;
import org.simplepoint.plugin.oidc.api.entity.AuthorizationConsent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository interface for managing OAuth2 authorization consents.
 *
 * <p>This repository provides methods to store, retrieve, and delete authorization
 * consent records associated with registered clients and principal names.
 * </p>
 */
@Repository
public interface JpaAuthorizationConsentRepository
    extends JpaRepository<AuthorizationConsent, AuthorizationConsent.AuthorizationConsentId> {

  /**
   * Finds an authorization consent by registered client ID and principal name.
   *
   * @param registeredClientId the client ID used to identify the OAuth2 client
   * @param principalName      the principal name representing the authenticated user
   * @return an {@link Optional} containing the found {@link AuthorizationConsent}, or empty if not found
   */
  Optional<AuthorizationConsent> findByRegisteredClientIdAndPrincipalName(
      String registeredClientId, String principalName);

  /**
   * Deletes an authorization consent record by registered client ID and principal name.
   *
   * @param registeredClientId the client ID associated with the consent record
   * @param principalName      the principal name of the authenticated user
   */
  void deleteByRegisteredClientIdAndPrincipalName(
      String registeredClientId, String principalName);
}
