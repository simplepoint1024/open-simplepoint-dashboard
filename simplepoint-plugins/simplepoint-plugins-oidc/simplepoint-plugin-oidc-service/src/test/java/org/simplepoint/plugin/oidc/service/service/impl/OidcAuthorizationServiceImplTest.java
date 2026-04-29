/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.oidc.service.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.oidc.api.entity.Authorization;
import org.simplepoint.plugin.oidc.service.repository.JpaAuthorizationRepository;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.security.jackson2.SecurityJackson2Modules;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.jackson2.OAuth2AuthorizationServerJackson2Module;

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

  @Test
  void saveShouldPersistAuthorizationCodeGrant() {
    RegisteredClient client = minimalClient("client-1");
    OAuth2Authorization auth = OAuth2Authorization.withRegisteredClient(client)
        .id("auth-1")
        .principalName("user1")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .build();

    service.save(auth);

    ArgumentCaptor<Authorization> captor = ArgumentCaptor.forClass(Authorization.class);
    verify(authorizationRepository).save(captor.capture());
    assertEquals("auth-1", captor.getValue().getId());
    assertEquals("authorization_code", captor.getValue().getAuthorizationGrantType());
  }

  @Test
  void saveShouldPersistClientCredentialsGrant() {
    RegisteredClient client = minimalClient("client-2");
    OAuth2Authorization auth = OAuth2Authorization.withRegisteredClient(client)
        .id("auth-2")
        .principalName("service-account")
        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
        .build();

    service.save(auth);

    ArgumentCaptor<Authorization> captor = ArgumentCaptor.forClass(Authorization.class);
    verify(authorizationRepository).save(captor.capture());
    assertEquals("client_credentials", captor.getValue().getAuthorizationGrantType());
  }

  @Test
  void saveShouldPersistRefreshTokenGrant() {
    RegisteredClient client = minimalClient("client-3");
    OAuth2Authorization auth = OAuth2Authorization.withRegisteredClient(client)
        .id("auth-3")
        .principalName("user3")
        .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
        .build();

    service.save(auth);

    ArgumentCaptor<Authorization> captor = ArgumentCaptor.forClass(Authorization.class);
    verify(authorizationRepository).save(captor.capture());
    assertEquals("refresh_token", captor.getValue().getAuthorizationGrantType());
  }

  @Test
  void saveShouldPersistDeviceCodeGrant() {
    RegisteredClient client = minimalClient("client-4");
    OAuth2Authorization auth = OAuth2Authorization.withRegisteredClient(client)
        .id("auth-4")
        .principalName("device-user")
        .authorizationGrantType(AuthorizationGrantType.DEVICE_CODE)
        .build();

    service.save(auth);

    ArgumentCaptor<Authorization> captor = ArgumentCaptor.forClass(Authorization.class);
    verify(authorizationRepository).save(captor.capture());
    assertEquals("urn:ietf:params:oauth:grant-type:device_code", captor.getValue().getAuthorizationGrantType());
  }

  @Test
  void saveShouldPersistCustomGrant() {
    RegisteredClient client = minimalClient("client-5");
    OAuth2Authorization auth = OAuth2Authorization.withRegisteredClient(client)
        .id("auth-5")
        .principalName("user5")
        .authorizationGrantType(new AuthorizationGrantType("custom_grant"))
        .build();

    service.save(auth);

    ArgumentCaptor<Authorization> captor = ArgumentCaptor.forClass(Authorization.class);
    verify(authorizationRepository).save(captor.capture());
    assertEquals("custom_grant", captor.getValue().getAuthorizationGrantType());
  }

  @Test
  void saveShouldSetStateAttributeWhenPresent() {
    RegisteredClient client = minimalClient("client-6");
    OAuth2Authorization auth = OAuth2Authorization.withRegisteredClient(client)
        .id("auth-6")
        .principalName("user6")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .attribute(OAuth2ParameterNames.STATE, "my-state-value")
        .build();

    service.save(auth);

    ArgumentCaptor<Authorization> captor = ArgumentCaptor.forClass(Authorization.class);
    verify(authorizationRepository).save(captor.capture());
    assertEquals("my-state-value", captor.getValue().getState());
  }

  @Test
  void saveShouldPersistAuthorizedScopes() {
    RegisteredClient client = minimalClient("client-7");
    OAuth2Authorization auth = OAuth2Authorization.withRegisteredClient(client)
        .id("auth-7")
        .principalName("user7")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .authorizedScopes(java.util.Set.of("openid", "profile"))
        .build();

    service.save(auth);

    ArgumentCaptor<Authorization> captor = ArgumentCaptor.forClass(Authorization.class);
    verify(authorizationRepository).save(captor.capture());
    assertNotNull(captor.getValue().getAuthorizedScopes());
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

  @Test
  void findByIdShouldThrowWhenRegisteredClientNotFound() {
    Authorization entity = minimalEntity("auth-1", "client-99", "authorization_code");
    when(authorizationRepository.findById("auth-1")).thenReturn(Optional.of(entity));
    when(registeredClientRepository.findById("client-99")).thenReturn(null);

    assertThrows(DataRetrievalFailureException.class, () -> service.findById("auth-1"));
  }

  @Test
  void findByIdShouldReturnConvertedAuthorizationWithMinimalEntity() {
    RegisteredClient client = minimalClient("client-1");
    Authorization entity = minimalEntity("auth-1", "client-1", "authorization_code");
    entity.setState("some-state");

    when(authorizationRepository.findById("auth-1")).thenReturn(Optional.of(entity));
    when(registeredClientRepository.findById("client-1")).thenReturn(client);

    OAuth2Authorization result = service.findById("auth-1");

    assertNotNull(result);
    assertEquals("auth-1", result.getId());
    assertEquals("user1", result.getPrincipalName());
    assertEquals(AuthorizationGrantType.AUTHORIZATION_CODE, result.getAuthorizationGrantType());
  }

  @Test
  void findByIdShouldResolveClientCredentialsGrantType() {
    RegisteredClient client = minimalClient("client-2");
    Authorization entity = minimalEntity("auth-2", "client-2", "client_credentials");

    when(authorizationRepository.findById("auth-2")).thenReturn(Optional.of(entity));
    when(registeredClientRepository.findById("client-2")).thenReturn(client);

    OAuth2Authorization result = service.findById("auth-2");

    assertNotNull(result);
    assertEquals(AuthorizationGrantType.CLIENT_CREDENTIALS, result.getAuthorizationGrantType());
  }

  @Test
  void findByIdShouldResolveRefreshTokenGrantType() {
    RegisteredClient client = minimalClient("client-3");
    Authorization entity = minimalEntity("auth-3", "client-3", "refresh_token");

    when(authorizationRepository.findById("auth-3")).thenReturn(Optional.of(entity));
    when(registeredClientRepository.findById("client-3")).thenReturn(client);

    OAuth2Authorization result = service.findById("auth-3");

    assertNotNull(result);
    assertEquals(AuthorizationGrantType.REFRESH_TOKEN, result.getAuthorizationGrantType());
  }

  @Test
  void findByIdShouldResolveDeviceCodeGrantType() {
    RegisteredClient client = minimalClient("client-4");
    Authorization entity = minimalEntity("auth-4", "client-4",
        "urn:ietf:params:oauth:grant-type:device_code");

    when(authorizationRepository.findById("auth-4")).thenReturn(Optional.of(entity));
    when(registeredClientRepository.findById("client-4")).thenReturn(client);

    OAuth2Authorization result = service.findById("auth-4");

    assertNotNull(result);
    assertEquals(AuthorizationGrantType.DEVICE_CODE, result.getAuthorizationGrantType());
  }

  @Test
  void findByIdShouldResolveCustomGrantType() {
    RegisteredClient client = minimalClient("client-5");
    Authorization entity = minimalEntity("auth-5", "client-5", "custom_grant_type");

    when(authorizationRepository.findById("auth-5")).thenReturn(Optional.of(entity));
    when(registeredClientRepository.findById("client-5")).thenReturn(client);

    OAuth2Authorization result = service.findById("auth-5");

    assertNotNull(result);
    assertEquals("custom_grant_type", result.getAuthorizationGrantType().getValue());
  }

  @Test
  void findByIdShouldPopulateAuthorizationCodeToken() {
    RegisteredClient client = minimalClient("client-10");
    Authorization entity = minimalEntity("auth-10", "client-10", "authorization_code");
    Instant now = Instant.now();
    entity.setAuthorizationCodeValue("auth-code-value");
    entity.setAuthorizationCodeIssuedAt(now);
    entity.setAuthorizationCodeExpiresAt(now.plusSeconds(600));
    entity.setAuthorizationCodeMetadata(emptyMapJson());

    when(authorizationRepository.findById("auth-10")).thenReturn(Optional.of(entity));
    when(registeredClientRepository.findById("client-10")).thenReturn(client);

    OAuth2Authorization result = service.findById("auth-10");

    assertNotNull(result);
    assertNotNull(result.getToken(org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode.class));
  }

  @Test
  void findByIdShouldPopulateAccessToken() {
    RegisteredClient client = minimalClient("client-11");
    Authorization entity = minimalEntity("auth-11", "client-11", "authorization_code");
    Instant now = Instant.now();
    entity.setAccessTokenValue("access-token-value");
    entity.setAccessTokenIssuedAt(now);
    entity.setAccessTokenExpiresAt(now.plusSeconds(3600));
    entity.setAccessTokenMetadata(emptyMapJson());
    entity.setAccessTokenScopes("openid,profile");

    when(authorizationRepository.findById("auth-11")).thenReturn(Optional.of(entity));
    when(registeredClientRepository.findById("client-11")).thenReturn(client);

    OAuth2Authorization result = service.findById("auth-11");

    assertNotNull(result);
    assertNotNull(result.getAccessToken());
  }

  @Test
  void findByIdShouldPopulateRefreshToken() {
    RegisteredClient client = minimalClient("client-12");
    Authorization entity = minimalEntity("auth-12", "client-12", "authorization_code");
    Instant now = Instant.now();
    entity.setRefreshTokenValue("refresh-token-value");
    entity.setRefreshTokenIssuedAt(now);
    entity.setRefreshTokenExpiresAt(now.plusSeconds(86400));
    entity.setRefreshTokenMetadata(emptyMapJson());

    when(authorizationRepository.findById("auth-12")).thenReturn(Optional.of(entity));
    when(registeredClientRepository.findById("client-12")).thenReturn(client);

    OAuth2Authorization result = service.findById("auth-12");

    assertNotNull(result);
    assertNotNull(result.getRefreshToken());
  }

  @Test
  void findByIdShouldPopulateUserCode() {
    RegisteredClient client = minimalClient("client-13");
    Authorization entity = minimalEntity("auth-13", "client-13",
        "urn:ietf:params:oauth:grant-type:device_code");
    Instant now = Instant.now();
    entity.setUserCodeValue("USER-CODE-1");
    entity.setUserCodeIssuedAt(now);
    entity.setUserCodeExpiresAt(now.plusSeconds(1800));
    entity.setUserCodeMetadata(emptyMapJson());

    when(authorizationRepository.findById("auth-13")).thenReturn(Optional.of(entity));
    when(registeredClientRepository.findById("client-13")).thenReturn(client);

    OAuth2Authorization result = service.findById("auth-13");

    assertNotNull(result);
    assertNotNull(result.getToken(org.springframework.security.oauth2.core.OAuth2UserCode.class));
  }

  @Test
  void findByIdShouldPopulateDeviceCode() {
    RegisteredClient client = minimalClient("client-14");
    Authorization entity = minimalEntity("auth-14", "client-14",
        "urn:ietf:params:oauth:grant-type:device_code");
    Instant now = Instant.now();
    entity.setDeviceCodeValue("device-code-value");
    entity.setDeviceCodeIssuedAt(now);
    entity.setDeviceCodeExpiresAt(now.plusSeconds(1800));
    entity.setDeviceCodeMetadata(emptyMapJson());

    when(authorizationRepository.findById("auth-14")).thenReturn(Optional.of(entity));
    when(registeredClientRepository.findById("client-14")).thenReturn(client);

    OAuth2Authorization result = service.findById("auth-14");

    assertNotNull(result);
    assertNotNull(result.getToken(org.springframework.security.oauth2.core.OAuth2DeviceCode.class));
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

  @Test
  void findByTokenShouldReturnConvertedAuthorizationWhenFound() {
    RegisteredClient client = minimalClient("client-20");
    Authorization entity = minimalEntity("auth-20", "client-20", "authorization_code");

    when(authorizationRepository.findByAccessTokenValue("at-value")).thenReturn(Optional.of(entity));
    when(registeredClientRepository.findById("client-20")).thenReturn(client);

    OAuth2Authorization result = service.findByToken("at-value", OAuth2TokenType.ACCESS_TOKEN);

    assertNotNull(result);
    assertEquals("auth-20", result.getId());
  }

  @Test
  void findByTokenWithNullTypeShouldReturnConvertedAuthorizationWhenFound() {
    RegisteredClient client = minimalClient("client-21");
    Authorization entity = minimalEntity("auth-21", "client-21", "refresh_token");

    when(authorizationRepository
        .findByStateOrAuthorizationCodeValueOrAccessTokenValueOrRefreshTokenValueOrOidcIdTokenValueOrUserCodeValueOrDeviceCodeValue("any-token"))
        .thenReturn(Optional.of(entity));
    when(registeredClientRepository.findById("client-21")).thenReturn(client);

    OAuth2Authorization result = service.findByToken("any-token", null);

    assertNotNull(result);
    assertEquals("auth-21", result.getId());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static RegisteredClient minimalClient(final String id) {
    return RegisteredClient.withId(id)
        .clientId("client-" + id)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
        .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
        .authorizationGrantType(AuthorizationGrantType.DEVICE_CODE)
        .redirectUri("http://localhost/callback")
        .build();
  }

  private static Authorization minimalEntity(final String id, final String clientId,
      final String grantType) {
    Authorization entity = new Authorization();
    entity.setId(id);
    entity.setRegisteredClientId(clientId);
    entity.setPrincipalName("user1");
    entity.setAuthorizationGrantType(grantType);
    entity.setAuthorizedScopes("");
    entity.setAttributes(emptyMapJson());
    return entity;
  }

  private static String emptyMapJson() {
    try {
      ObjectMapper mapper = new ObjectMapper();
      ClassLoader cl = OidcAuthorizationServiceImpl.class.getClassLoader();
      List<Module> securityModules = SecurityJackson2Modules.getModules(cl);
      mapper.registerModules(securityModules);
      mapper.registerModule(new OAuth2AuthorizationServerJackson2Module());
      mapper.activateDefaultTyping(
          LaissezFaireSubTypeValidator.instance,
          ObjectMapper.DefaultTyping.NON_FINAL,
          JsonTypeInfo.As.PROPERTY
      );
      return mapper.writeValueAsString(new HashMap<>());
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

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
