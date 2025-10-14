/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package  org.springframework.boot.autoconfigure.security.oauth2.server.servlet;

import java.util.List;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

/**
 * A delegate class for mapping {@link OAuth2AuthorizationServerProperties} to {@link RegisteredClient} objects.
 *
 * <p>This class acts as a wrapper around {@link OAuth2AuthorizationServerPropertiesMapper}, ensuring
 * that OAuth2 authorization server properties are converted into a list of registered clients.
 * </p>
 */
public class AuthorizationServerPropertiesMapperDelegate {
  /**
   * Delegate responsible for mapping authorization server properties.
   */
  private final OAuth2AuthorizationServerPropertiesMapper delegate;

  /**
   * Constructs an instance of {@code AuthorizationServerPropertiesMapperDelegate}.
   *
   * @param properties the authorization server properties to map
   * @throws IllegalArgumentException if {@code properties} is null
   */
  public AuthorizationServerPropertiesMapperDelegate(
      OAuth2AuthorizationServerProperties properties) {
    this.delegate = new OAuth2AuthorizationServerPropertiesMapper(properties);
  }

  /**
   * Converts the configured authorization server properties into a list of {@link RegisteredClient} objects.
   *
   * @return a list of registered OAuth2 clients
   */
  public List<RegisteredClient> asRegisteredClients() {
    return this.delegate.asRegisteredClients();
  }
}
