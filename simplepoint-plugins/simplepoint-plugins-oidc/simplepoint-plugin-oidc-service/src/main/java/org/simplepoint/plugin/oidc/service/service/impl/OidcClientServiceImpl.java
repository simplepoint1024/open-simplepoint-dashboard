/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.oidc.service.service.impl;

import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.core.context.UserContext;
import org.simplepoint.plugin.oidc.api.entity.Client;
import org.simplepoint.plugin.oidc.api.repository.OidcClientRepository;
import org.simplepoint.plugin.oidc.api.service.OidcClientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Component;

/**
 * Implementation of {@link RegisteredClientRepository} that manages OAuth2 clients,
 * providing functionality to store and retrieve registered clients from a repository.
 * This service handles the persistence of OAuth2 client authentication and authorization data.
 * Clients are stored using {@code ClientRepository}, and their configurations are mapped
 * between {@link RegisteredClient} and {@code Client} entity.
 */
@Component
public class OidcClientServiceImpl extends BaseServiceImpl<OidcClientRepository, Client, String> implements OidcClientService {

  /**
   * Constructs a new OidcClientServiceImpl with the specified repository, user context, and details provider service.
   *
   * @param repository             the OidcClientRepository instance for data access
   * @param userContext            the user context for retrieving current user information
   * @param detailsProviderService the service for providing user details
   */
  public OidcClientServiceImpl(
      OidcClientRepository repository,
      @Autowired(required = false) UserContext<BaseUser> userContext,
      DetailsProviderService detailsProviderService
  ) {
    super(repository, userContext, detailsProviderService);
  }
}
