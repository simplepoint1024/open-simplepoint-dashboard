/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.oidc.service.service.impl;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.oidc.api.entity.AuthorizationConsent;
import org.simplepoint.plugin.oidc.service.repository.JpaAuthorizationConsentRepository;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

@ExtendWith(MockitoExtension.class)
class OidcAuthorizationConsentServiceImplTest {

  @Mock
  private JpaAuthorizationConsentRepository authorizationConsentRepository;

  @Mock
  private RegisteredClientRepository registeredClientRepository;

  private OidcAuthorizationConsentServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new OidcAuthorizationConsentServiceImpl(
        authorizationConsentRepository, registeredClientRepository);
  }

  // ── constructor guards ────────────────────────────────────────────────────

  @Test
  void constructorShouldThrowWhenConsentRepositoryIsNull() {
    assertThrows(IllegalArgumentException.class,
        () -> new OidcAuthorizationConsentServiceImpl(null, registeredClientRepository));
  }

  @Test
  void constructorShouldThrowWhenRegisteredClientRepositoryIsNull() {
    assertThrows(IllegalArgumentException.class,
        () -> new OidcAuthorizationConsentServiceImpl(authorizationConsentRepository, null));
  }

  // ── save ──────────────────────────────────────────────────────────────────

  @Test
  void saveShouldThrowWhenConsentIsNull() {
    assertThrows(IllegalArgumentException.class, () -> service.save(null));
  }

  @Test
  void saveShouldPersistConsentEntity() {
    OAuth2AuthorizationConsent consent = OAuth2AuthorizationConsent
        .withId("client-1", "user-1")
        .scope("read")
        .build();
    when(authorizationConsentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.save(consent);

    verify(authorizationConsentRepository).save(any(AuthorizationConsent.class));
  }

  // ── remove ────────────────────────────────────────────────────────────────

  @Test
  void removeShouldThrowWhenConsentIsNull() {
    assertThrows(IllegalArgumentException.class, () -> service.remove(null));
  }

  @Test
  void removeShouldDelegateDeleteToRepository() {
    OAuth2AuthorizationConsent consent = OAuth2AuthorizationConsent
        .withId("client-1", "user-1")
        .scope("read")
        .build();

    service.remove(consent);

    verify(authorizationConsentRepository)
        .deleteByRegisteredClientIdAndPrincipalName("client-1", "user-1");
  }

  // ── findById ──────────────────────────────────────────────────────────────

  @Test
  void findByIdShouldThrowWhenRegisteredClientIdIsBlank() {
    assertThrows(IllegalArgumentException.class,
        () -> service.findById("  ", "user-1"));
  }

  @Test
  void findByIdShouldThrowWhenPrincipalNameIsBlank() {
    assertThrows(IllegalArgumentException.class,
        () -> service.findById("client-1", "  "));
  }

  @Test
  void findByIdShouldReturnNullWhenConsentNotFound() {
    when(authorizationConsentRepository.findByRegisteredClientIdAndPrincipalName("c1", "u1"))
        .thenReturn(Optional.empty());
    assertNull(service.findById("c1", "u1"));
  }

  @Test
  void findByIdShouldReturnConsentWhenFound() {
    AuthorizationConsent entity = new AuthorizationConsent();
    entity.setRegisteredClientId("c1");
    entity.setPrincipalName("u1");
    entity.setAuthorities("SCOPE_read");

    RegisteredClient registeredClient = RegisteredClient.withId("c1")
        .clientId("client-app")
        .clientSecret("{noop}secret")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
        .redirectUri("http://localhost/callback")
        .scope("read")
        .build();

    when(authorizationConsentRepository.findByRegisteredClientIdAndPrincipalName("c1", "u1"))
        .thenReturn(Optional.of(entity));
    when(registeredClientRepository.findById("c1")).thenReturn(registeredClient);

    OAuth2AuthorizationConsent result = service.findById("c1", "u1");
    org.junit.jupiter.api.Assertions.assertNotNull(result);
    org.junit.jupiter.api.Assertions.assertEquals("c1", result.getRegisteredClientId());
    org.junit.jupiter.api.Assertions.assertEquals("u1", result.getPrincipalName());
  }
}
