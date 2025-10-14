/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package  org.simplepoint.plugin.oidc.service.service.impl;

import java.util.HashSet;
import java.util.Set;
import org.simplepoint.plugin.oidc.api.entity.AuthorizationConsent;
import org.simplepoint.plugin.oidc.service.repository.JpaAuthorizationConsentRepository;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;


/**
 * Implementation of {@link OAuth2AuthorizationConsentService} that stores OAuth2 authorization
 * consent information using a repository.
 * This service manages OAuth2 authorization consents, allowing clients to save, remove, and
 * retrieve user consent information related to registered clients.
 */
@Component
public class Oauth2AuthorizationConsentServiceImpl implements OAuth2AuthorizationConsentService {

  /**
   * Repository for storing authorization consent data.
   */
  private final JpaAuthorizationConsentRepository authorizationConsentRepository;

  /**
   * Repository for retrieving registered client information.
   */
  private final RegisteredClientRepository registeredClientRepository;

  /**
   * Constructs an instance of {@code OAuth2AuthorizationConsentServiceImpl}.
   *
   * @param authorizationConsentRepository the repository for storing authorization consent data
   * @param registeredClientRepository     the repository for retrieving registered clients
   * @throws IllegalArgumentException if any parameter is null
   */
  public Oauth2AuthorizationConsentServiceImpl(
      JpaAuthorizationConsentRepository authorizationConsentRepository,
      RegisteredClientRepository registeredClientRepository) {
    Assert.notNull(authorizationConsentRepository, "authorizationConsentRepository cannot be null");
    Assert.notNull(registeredClientRepository, "registeredClientRepository cannot be null");
    this.authorizationConsentRepository = authorizationConsentRepository;
    this.registeredClientRepository = registeredClientRepository;
  }

  /**
   * Saves the provided OAuth2 authorization consent.
   *
   * @param authorizationConsent the authorization consent to save
   * @throws IllegalArgumentException if {@code authorizationConsent} is null
   */
  @Override
  public void save(OAuth2AuthorizationConsent authorizationConsent) {
    Assert.notNull(authorizationConsent, "authorizationConsent cannot be null");
    this.authorizationConsentRepository.save(toEntity(authorizationConsent));
  }

  /**
   * Removes the specified OAuth2 authorization consent.
   *
   * @param authorizationConsent the authorization consent to remove
   * @throws IllegalArgumentException if {@code authorizationConsent} is null
   */
  @Override
  public void remove(OAuth2AuthorizationConsent authorizationConsent) {
    Assert.notNull(authorizationConsent, "authorizationConsent cannot be null");
    this.authorizationConsentRepository.deleteByRegisteredClientIdAndPrincipalName(
        authorizationConsent.getRegisteredClientId(), authorizationConsent.getPrincipalName());
  }

  /**
   * Finds an OAuth2 authorization consent by client ID and principal name.
   *
   * @param registeredClientId the registered client ID
   * @param principalName      the principal name
   * @return the found {@link OAuth2AuthorizationConsent}, or {@code null} if not found
   * @throws IllegalArgumentException if {@code registeredClientId} or {@code principalName} is empty
   */
  @Override
  public OAuth2AuthorizationConsent findById(String registeredClientId, String principalName) {
    Assert.hasText(registeredClientId, "registeredClientId cannot be empty");
    Assert.hasText(principalName, "principalName cannot be empty");
    return this.authorizationConsentRepository.findByRegisteredClientIdAndPrincipalName(
        registeredClientId, principalName).map(this::toObject).orElse(null);
  }

  /**
   * Converts an {@link AuthorizationConsent} entity into an {@link OAuth2AuthorizationConsent} object.
   *
   * @param authorizationConsent the entity to convert
   * @return the converted {@link OAuth2AuthorizationConsent}
   * @throws DataRetrievalFailureException if the registered client cannot be found
   */
  private OAuth2AuthorizationConsent toObject(AuthorizationConsent authorizationConsent) {
    String registeredClientId = authorizationConsent.getRegisteredClientId();
    RegisteredClient registeredClient =
        this.registeredClientRepository.findById(registeredClientId);
    if (registeredClient == null) {
      throw new DataRetrievalFailureException(
          "The RegisteredClient with id '" + registeredClientId
              + "' was not found in the RegisteredClientRepository.");
    }

    OAuth2AuthorizationConsent.Builder builder = OAuth2AuthorizationConsent.withId(
        registeredClientId, authorizationConsent.getPrincipalName());
    if (authorizationConsent.getAuthorities() != null) {
      for (String authority : StringUtils.commaDelimitedListToSet(
          authorizationConsent.getAuthorities())) {
        builder.authority(new SimpleGrantedAuthority(authority));
      }
    }

    return builder.build();
  }

  /**
   * Converts an {@link OAuth2AuthorizationConsent} object into an {@link AuthorizationConsent} entity.
   *
   * @param authorizationConsent the object to convert
   * @return the converted {@link AuthorizationConsent} entity
   */
  private AuthorizationConsent toEntity(OAuth2AuthorizationConsent authorizationConsent) {
    AuthorizationConsent entity = new AuthorizationConsent();
    entity.setRegisteredClientId(authorizationConsent.getRegisteredClientId());
    entity.setPrincipalName(authorizationConsent.getPrincipalName());

    Set<String> authorities = new HashSet<>();
    for (GrantedAuthority authority : authorizationConsent.getAuthorities()) {
      authorities.add(authority.getAuthority());
    }
    entity.setAuthorities(StringUtils.collectionToCommaDelimitedString(authorities));

    return entity;
  }
}