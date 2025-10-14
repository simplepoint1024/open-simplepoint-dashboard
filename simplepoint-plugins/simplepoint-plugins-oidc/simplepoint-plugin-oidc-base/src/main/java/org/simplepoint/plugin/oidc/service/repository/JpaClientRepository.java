/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.oidc.service.repository;

import java.util.Optional;
import org.simplepoint.plugin.oidc.api.entity.Client;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository interface for managing OAuth2 client entities.
 *
 * <p>* This repository provides methods to store and retrieve OAuth2 client configurations.
 * It allows querying clients by their unique client ID.
 * </p>
 */
@Repository
public interface JpaClientRepository extends JpaRepository<Client, String> {

  /**
   * Finds a client by its client ID.
   *
   * @param clientId the unique client identifier
   * @return an {@link Optional} containing the found {@link Client}, or empty if not found
   */
  Optional<Client> findByClientId(String clientId);
}