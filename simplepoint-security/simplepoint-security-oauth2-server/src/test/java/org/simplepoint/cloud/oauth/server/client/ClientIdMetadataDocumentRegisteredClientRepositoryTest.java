/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.cloud.oauth.server.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

class ClientIdMetadataDocumentRegisteredClientRepositoryTest {

  @Test
  void resolveMetadata_buildsPublicPkceClient() {
    final ClientIdMetadataDocumentRegisteredClientRepository repository = repository(
        new MemoryRegisteredClientRepository()
    );
    String clientId = "https://client.example/oauth/client.json";

    RegisteredClient client = repository.resolveMetadata(clientId, Map.of(
        "client_id", clientId,
        "client_name", "Example MCP Client",
        "redirect_uris", List.of(
            "https://client.example/callback",
            "http://127.0.0.1:4567/callback"
        ),
        "grant_types", List.of("authorization_code", "refresh_token"),
        "response_types", List.of("code"),
        "token_endpoint_auth_method", "none",
        "scope", "mcp.invoke"
    ));

    assertThat(client.getClientId()).isEqualTo(clientId);
    assertThat(client.getClientAuthenticationMethods())
        .containsExactly(ClientAuthenticationMethod.NONE);
    assertThat(client.getAuthorizationGrantTypes())
        .containsExactlyInAnyOrder(
            AuthorizationGrantType.AUTHORIZATION_CODE,
            AuthorizationGrantType.REFRESH_TOKEN
        );
    assertThat(client.getClientSettings().isRequireProofKey()).isTrue();
    assertThat(client.getClientSettings().isRequireAuthorizationConsent()).isTrue();
    assertThat(client.getScopes()).containsExactly("mcp.invoke");
  }

  @Test
  void resolveMetadata_rejectsIdentityMismatchAndSecretAuthentication() {
    final ClientIdMetadataDocumentRegisteredClientRepository repository = repository(
        new MemoryRegisteredClientRepository()
    );
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("client_id", "https://attacker.example/client.json");
    metadata.put("client_name", "Attacker");
    metadata.put("redirect_uris", List.of("https://attacker.example/callback"));

    assertThatThrownBy(() -> repository.resolveMetadata(
        "https://client.example/client.json",
        metadata
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exactly match");

    metadata.put("client_id", "https://client.example/client.json");
    metadata.put("token_endpoint_auth_method", "client_secret_post");
    assertThatThrownBy(() -> repository.resolveMetadata(
        "https://client.example/client.json",
        metadata
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("public");
  }

  @Test
  void findByClientId_prefersPreRegisteredClient() {
    MemoryRegisteredClientRepository delegate = new MemoryRegisteredClientRepository();
    RegisteredClient registered = RegisteredClient.withId("static-id")
        .clientId("https://client.example/client.json")
        .clientSecret("{noop}secret")
        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("https://client.example/callback")
        .build();
    delegate.save(registered);

    RegisteredClient resolved = repository(delegate)
        .findByClientId(registered.getClientId());

    assertThat(resolved).isSameAs(registered);
  }

  private static ClientIdMetadataDocumentRegisteredClientRepository repository(
      final RegisteredClientRepository delegate
  ) {
    ClientIdMetadataDocumentProperties properties =
        new ClientIdMetadataDocumentProperties();
    properties.setAllowedScopes(java.util.Set.of("mcp.invoke"));
    return new ClientIdMetadataDocumentRegisteredClientRepository(
        delegate,
        properties,
        new ObjectMapper()
    );
  }

  private static final class MemoryRegisteredClientRepository
      implements RegisteredClientRepository {

    private final Map<String, RegisteredClient> byId = new LinkedHashMap<>();

    private final Map<String, RegisteredClient> byClientId = new LinkedHashMap<>();

    @Override
    public void save(final RegisteredClient registeredClient) {
      byId.put(registeredClient.getId(), registeredClient);
      byClientId.put(registeredClient.getClientId(), registeredClient);
    }

    @Override
    public RegisteredClient findById(final String id) {
      return byId.get(id);
    }

    @Override
    public RegisteredClient findByClientId(final String clientId) {
      return byClientId.get(clientId);
    }
  }
}
