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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.oidc.api.entity.Authorization;
import org.simplepoint.plugin.oidc.service.repository.JpaAuthorizationRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

@ExtendWith(MockitoExtension.class)
class OidcAuthorizationServiceImplTest {

  @Mock
  private JpaAuthorizationRepository authorizationRepository;

  @Mock
  private RegisteredClientRepository registeredClientRepository;

  private OidcAuthorizationServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new OidcAuthorizationServiceImpl(authorizationRepository, registeredClientRepository);
  }

  // ── constructor guards ────────────────────────────────────────────────────

  @Test
  void constructorShouldThrowWhenAuthorizationRepositoryIsNull() {
    assertThrows(IllegalArgumentException.class,
        () -> new OidcAuthorizationServiceImpl(null, registeredClientRepository));
  }

  @Test
  void constructorShouldThrowWhenRegisteredClientRepositoryIsNull() {
    assertThrows(IllegalArgumentException.class,
        () -> new OidcAuthorizationServiceImpl(authorizationRepository, null));
  }

  // ── save ──────────────────────────────────────────────────────────────────

  @Test
  void saveShouldThrowWhenAuthorizationIsNull() {
    assertThrows(IllegalArgumentException.class, () -> service.save(null));
  }

  // ── remove ────────────────────────────────────────────────────────────────

  @Test
  void removeShouldThrowWhenAuthorizationIsNull() {
    assertThrows(IllegalArgumentException.class, () -> service.remove(null));
  }

  @Test
  void removeShouldDelegateDeleteToRepository() {
    OAuth2Authorization auth = minimalAuthorization("auth-1");
    service.remove(auth);
    verify(authorizationRepository).deleteById("auth-1");
  }

  // ── findById ──────────────────────────────────────────────────────────────

  @Test
  void findByIdShouldThrowWhenIdIsBlank() {
    assertThrows(IllegalArgumentException.class, () -> service.findById("  "));
  }

  @Test
  void findByIdShouldReturnNullWhenNotFound() {
    when(authorizationRepository.findById("missing")).thenReturn(Optional.empty());
    assertNull(service.findById("missing"));
  }

  // ── findByToken – token-type routing ─────────────────────────────────────

  @Test
  void findByTokenShouldThrowWhenTokenIsBlank() {
    assertThrows(IllegalArgumentException.class,
        () -> service.findByToken("  ", OAuth2TokenType.ACCESS_TOKEN));
  }

  @Test
  void findByTokenWithNullTokenTypeShouldCallFindByAny() {
    when(authorizationRepository
        .findByStateOrAuthorizationCodeValueOrAccessTokenValueOrRefreshTokenValueOrOidcIdTokenValueOrUserCodeValueOrDeviceCodeValue("tok"))
        .thenReturn(Optional.empty());

    assertNull(service.findByToken("tok", null));

    verify(authorizationRepository)
        .findByStateOrAuthorizationCodeValueOrAccessTokenValueOrRefreshTokenValueOrOidcIdTokenValueOrUserCodeValueOrDeviceCodeValue("tok");
  }

  @Test
  void findByTokenWithStateTypeShouldCallFindByState() {
    when(authorizationRepository.findByState("s1")).thenReturn(Optional.empty());
    assertNull(service.findByToken("s1", new OAuth2TokenType(OAuth2ParameterNames.STATE)));
    verify(authorizationRepository).findByState("s1");
  }

  @Test
  void findByTokenWithCodeTypeShouldCallFindByAuthorizationCodeValue() {
    when(authorizationRepository.findByAuthorizationCodeValue("code1")).thenReturn(Optional.empty());
    assertNull(service.findByToken("code1", new OAuth2TokenType(OAuth2ParameterNames.CODE)));
    verify(authorizationRepository).findByAuthorizationCodeValue("code1");
  }

  @Test
  void findByTokenWithAccessTokenTypeShouldCallFindByAccessTokenValue() {
    when(authorizationRepository.findByAccessTokenValue("at1")).thenReturn(Optional.empty());
    assertNull(service.findByToken("at1", OAuth2TokenType.ACCESS_TOKEN));
    verify(authorizationRepository).findByAccessTokenValue("at1");
  }

  @Test
  void findByTokenWithRefreshTokenTypeShouldCallFindByRefreshTokenValue() {
    when(authorizationRepository.findByRefreshTokenValue("rt1")).thenReturn(Optional.empty());
    assertNull(service.findByToken("rt1", OAuth2TokenType.REFRESH_TOKEN));
    verify(authorizationRepository).findByRefreshTokenValue("rt1");
  }

  @Test
  void findByTokenWithIdTokenTypeShouldCallFindByOidcIdTokenValue() {
    when(authorizationRepository.findByOidcIdTokenValue("idt1")).thenReturn(Optional.empty());
    assertNull(service.findByToken("idt1", new OAuth2TokenType(OidcParameterNames.ID_TOKEN)));
    verify(authorizationRepository).findByOidcIdTokenValue("idt1");
  }

  @Test
  void findByTokenWithUserCodeTypeShouldCallFindByUserCodeValue() {
    when(authorizationRepository.findByUserCodeValue("uc1")).thenReturn(Optional.empty());
    assertNull(service.findByToken("uc1", new OAuth2TokenType(OAuth2ParameterNames.USER_CODE)));
    verify(authorizationRepository).findByUserCodeValue("uc1");
  }

  @Test
  void findByTokenWithDeviceCodeTypeShouldCallFindByDeviceCodeValue() {
    when(authorizationRepository.findByDeviceCodeValue("dc1")).thenReturn(Optional.empty());
    assertNull(service.findByToken("dc1", new OAuth2TokenType(OAuth2ParameterNames.DEVICE_CODE)));
    verify(authorizationRepository).findByDeviceCodeValue("dc1");
  }

  @Test
  void findByTokenWithUnknownTypeShouldReturnNull() {
    assertNull(service.findByToken("x", new OAuth2TokenType("unknown_type")));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static OAuth2Authorization minimalAuthorization(final String id) {
    return org.mockito.Mockito.mock(OAuth2Authorization.class,
        invocation -> {
          if ("getId".equals(invocation.getMethod().getName())) {
            return id;
          }
          return invocation.callRealMethod();
        });
  }
}
