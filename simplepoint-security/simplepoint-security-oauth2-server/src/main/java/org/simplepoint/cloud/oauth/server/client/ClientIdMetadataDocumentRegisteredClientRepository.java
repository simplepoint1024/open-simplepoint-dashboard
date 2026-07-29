/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.cloud.oauth.server.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Resolves unregistered HTTPS client identifiers as OAuth Client ID Metadata Documents.
 *
 * <p>Pre-registered clients always win. Successfully validated metadata is persisted through
 * the delegate so an authorization code can still be exchanged after a process restart.
 * Persisted metadata clients are revalidated after the bounded in-memory cache expires.</p>
 */
@Slf4j
public class ClientIdMetadataDocumentRegisteredClientRepository
    implements RegisteredClientRepository {

  static final String DOCUMENT_URI_SETTING =
      "settings.client-id-metadata-document-uri";

  static final String FETCHED_AT_SETTING =
      "settings.client-id-metadata-document-fetched-at";

  private static final TypeReference<Map<String, Object>> MAP_TYPE =
      new TypeReference<>() {
      };

  private static final Set<String> SUPPORTED_GRANTS =
      Set.of("authorization_code", "refresh_token");

  private final RegisteredClientRepository delegate;

  private final ClientIdMetadataDocumentProperties properties;

  private final ObjectMapper objectMapper;

  private final HttpClient httpClient;

  private final Map<String, CacheEntry> cache =
      java.util.Collections.synchronizedMap(
          new LinkedHashMap<>(16, 0.75f, true)
      );

  /**
   * Creates a metadata-aware client repository.
   *
   * @param delegate persistent registered-client repository
   * @param properties document security limits
   * @param objectMapper JSON mapper
   */
  public ClientIdMetadataDocumentRegisteredClientRepository(
      final RegisteredClientRepository delegate,
      final ClientIdMetadataDocumentProperties properties,
      final ObjectMapper objectMapper
  ) {
    this(
        delegate,
        properties,
        objectMapper,
        HttpClient.newBuilder()
            .connectTimeout(positive(properties.getConnectTimeout(), Duration.ofSeconds(5)))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    );
  }

  ClientIdMetadataDocumentRegisteredClientRepository(
      final RegisteredClientRepository delegate,
      final ClientIdMetadataDocumentProperties properties,
      final ObjectMapper objectMapper,
      final HttpClient httpClient
  ) {
    this.delegate = delegate;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
  }

  @Override
  public void save(final RegisteredClient registeredClient) {
    delegate.save(registeredClient);
  }

  @Override
  public RegisteredClient findById(final String id) {
    Assert.hasText(id, "id cannot be empty");
    RegisteredClient registeredClient = delegate.findById(id);
    if (registeredClient == null || !metadataDocumentClient(registeredClient)) {
      return registeredClient;
    }
    return findByClientId(registeredClient.getClientId());
  }

  @Override
  public RegisteredClient findByClientId(final String clientId) {
    Assert.hasText(clientId, "clientId cannot be empty");
    RegisteredClient registeredClient = delegate.findByClientId(clientId);
    if (registeredClient != null && !metadataDocumentClient(registeredClient)) {
      return registeredClient;
    }
    if (!properties.isEnabled() || !looksLikeDocumentUri(clientId)) {
      return registeredClient;
    }
    CacheEntry cached = cached(clientId);
    if (cached != null) {
      return cached.registeredClient();
    }
    try {
      RegisteredClient resolved = resolve(clientId);
      delegate.save(resolved);
      cache(clientId, resolved);
      return resolved;
    } catch (RuntimeException ex) {
      log.warn("OAuth Client ID Metadata Document rejected for {}", safeOrigin(clientId));
      return null;
    }
  }

  RegisteredClient resolveMetadata(
      final String clientId,
      final Map<String, Object> metadata
  ) {
    if (!clientId.equals(string(metadata, "client_id"))) {
      throw new IllegalArgumentException("Client metadata client_id must exactly match its URL");
    }
    final String clientName = requiredString(metadata, "client_name");
    List<String> redirectUris = stringList(metadata.get("redirect_uris"));
    if (redirectUris.isEmpty()
        || redirectUris.size() > positive(properties.getMaxRedirectUris(), 20)) {
      throw new IllegalArgumentException("Client metadata redirect_uris is invalid");
    }
    redirectUris.forEach(this::validateRedirectUri);

    String authenticationMethod = normalized(
        string(metadata, "token_endpoint_auth_method"),
        ClientAuthenticationMethod.NONE.getValue()
    );
    if (!ClientAuthenticationMethod.NONE.getValue().equals(authenticationMethod)) {
      throw new IllegalArgumentException(
          "Only public Client ID Metadata Document clients are supported"
      );
    }

    Set<String> grants = normalizedValues(
        metadata.get("grant_types"),
        Set.of(AuthorizationGrantType.AUTHORIZATION_CODE.getValue())
    );
    if (!SUPPORTED_GRANTS.containsAll(grants)
        || !grants.contains(AuthorizationGrantType.AUTHORIZATION_CODE.getValue())) {
      throw new IllegalArgumentException("Client metadata grant_types is not supported");
    }
    Set<String> responseTypes = normalizedValues(
        metadata.get("response_types"),
        Set.of("code")
    );
    if (!Set.of("code").equals(responseTypes)) {
      throw new IllegalArgumentException("Client metadata response_types must contain only code");
    }

    Set<String> scopes = scopes(metadata.get("scope"));
    RegisteredClient.Builder builder = RegisteredClient
        .withId(metadataClientId(clientId))
        .clientId(clientId)
        .clientIdIssuedAt(Instant.now())
        // The legacy client table requires a non-null value. This random, server-only
        // placeholder is never accepted because the client authentication method is none.
        .clientSecret("{noop}cimd-unused-" + metadataClientId(clientId))
        .clientName(clientName)
        .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
        .redirectUris(values -> values.addAll(redirectUris))
        .scopes(values -> values.addAll(scopes))
        .clientSettings(ClientSettings.builder()
            .requireAuthorizationConsent(true)
            .requireProofKey(true)
            .setting(DOCUMENT_URI_SETTING, clientId)
            .setting(FETCHED_AT_SETTING, Instant.now().toString())
            .build());
    grants.forEach(value -> builder.authorizationGrantType(
        AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(value)
            ? AuthorizationGrantType.REFRESH_TOKEN
            : AuthorizationGrantType.AUTHORIZATION_CODE
    ));
    return builder.build();
  }

  private RegisteredClient resolve(final String clientId) {
    URI endpoint = validatedDocumentUri(clientId);
    HttpRequest request = HttpRequest.newBuilder(endpoint)
        .timeout(positive(properties.getRequestTimeout(), Duration.ofSeconds(10)))
        .header("Accept", "application/json, application/*+json")
        .GET()
        .build();
    HttpResponse<byte[]> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException ex) {
      throw new IllegalArgumentException("Client metadata request failed", ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Client metadata request was interrupted", ex);
    }
    if (response.statusCode() != 200) {
      throw new IllegalArgumentException("Client metadata endpoint must return HTTP 200");
    }
    if (response.body().length > positive(properties.getMaxResponseBytes(), 64 * 1024)) {
      throw new IllegalArgumentException("Client metadata response is too large");
    }
    String contentType = response.headers().firstValue("Content-Type")
        .orElse("")
        .toLowerCase(Locale.ROOT);
    if (!contentType.startsWith("application/json")
        && !(contentType.startsWith("application/") && contentType.contains("+json"))) {
      throw new IllegalArgumentException("Client metadata response must be JSON");
    }
    try {
      return resolveMetadata(clientId, objectMapper.readValue(response.body(), MAP_TYPE));
    } catch (IOException ex) {
      throw new IllegalArgumentException("Client metadata response is not valid JSON", ex);
    }
  }

  private URI validatedDocumentUri(final String clientId) {
    if (clientId.length() > positive(properties.getMaxDocumentUriLength(), 2048)) {
      throw new IllegalArgumentException("Client metadata URL is too long");
    }
    URI uri;
    try {
      uri = URI.create(clientId);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Client metadata URL is invalid", ex);
    }
    boolean insecureLocalhost = properties.isAllowInsecureLocalhost()
        && "http".equalsIgnoreCase(uri.getScheme()) && localhost(uri.getHost());
    if ((!"https".equalsIgnoreCase(uri.getScheme()) && !insecureLocalhost)
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getFragment() != null
        || uri.getQuery() != null
        || uri.getRawPath() == null
        || uri.getRawPath().isBlank()
        || "/".equals(uri.getRawPath())
        || dotSegment(uri.getRawPath())) {
      throw new IllegalArgumentException("Client metadata URL does not meet CIMD requirements");
    }
    rejectPrivateDestination(uri, insecureLocalhost);
    return uri;
  }

  private void rejectPrivateDestination(final URI uri, final boolean insecureLocalhost) {
    if (insecureLocalhost) {
      return;
    }
    try {
      for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
        if (address.isAnyLocalAddress()
            || address.isLoopbackAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
          throw new IllegalArgumentException(
              "Client metadata URL must not resolve to a private network"
          );
        }
      }
    } catch (IOException ex) {
      throw new IllegalArgumentException("Client metadata host cannot be resolved", ex);
    }
  }

  private void validateRedirectUri(final String value) {
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Client metadata redirect URI is invalid", ex);
    }
    boolean loopbackHttp = "http".equalsIgnoreCase(uri.getScheme())
        && localhost(uri.getHost());
    if ((!loopbackHttp && !"https".equalsIgnoreCase(uri.getScheme()))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getFragment() != null) {
      throw new IllegalArgumentException("Client metadata redirect URI is not allowed");
    }
  }

  private Set<String> scopes(final Object rawScope) {
    Set<String> allowed = new LinkedHashSet<>();
    if (properties.getAllowedScopes() != null) {
      properties.getAllowedScopes().stream()
          .filter(StringUtils::hasText)
          .map(String::trim)
          .forEach(allowed::add);
    }
    Set<String> requested = new LinkedHashSet<>();
    String configured = rawScope instanceof String text ? text : null;
    if (StringUtils.hasText(configured)) {
      for (String value : configured.trim().split("\\s+")) {
        if (!value.isBlank()) {
          requested.add(value);
        }
      }
    } else {
      requested.addAll(allowed);
    }
    if (requested.isEmpty() || !allowed.containsAll(requested)) {
      throw new IllegalArgumentException("Client metadata requests an unsupported scope");
    }
    return requested;
  }

  private CacheEntry cached(final String clientId) {
    CacheEntry entry = cache.get(clientId);
    if (entry == null) {
      return null;
    }
    if (!entry.expiresAt().isAfter(Instant.now())) {
      cache.remove(clientId);
      return null;
    }
    return entry;
  }

  private void cache(final String clientId, final RegisteredClient registeredClient) {
    int limit = positive(properties.getMaxCacheEntries(), 1000);
    synchronized (cache) {
      while (cache.size() >= limit && !cache.isEmpty()) {
        cache.remove(cache.keySet().iterator().next());
      }
      cache.put(clientId, new CacheEntry(
          registeredClient,
          Instant.now().plus(positive(properties.getCacheTtl(), Duration.ofMinutes(5)))
      ));
    }
  }

  private static boolean metadataDocumentClient(final RegisteredClient client) {
    Object value = client.getClientSettings().getSetting(DOCUMENT_URI_SETTING);
    return client.getClientId().equals(value);
  }

  private static boolean looksLikeDocumentUri(final String clientId) {
    return clientId.startsWith("https://") || clientId.startsWith("http://");
  }

  private static boolean localhost(final String host) {
    if (host == null) {
      return false;
    }
    String normalized = host.toLowerCase(Locale.ROOT);
    return "localhost".equals(normalized)
        || normalized.endsWith(".localhost")
        || "127.0.0.1".equals(normalized)
        || "::1".equals(normalized)
        || "[::1]".equals(normalized);
  }

  private static boolean dotSegment(final String rawPath) {
    for (String segment : rawPath.split("/")) {
      if (".".equals(segment) || "..".equals(segment)
          || "%2e".equalsIgnoreCase(segment)
          || "%2e%2e".equalsIgnoreCase(segment)) {
        return true;
      }
    }
    return false;
  }

  private static String metadataClientId(final String clientId) {
    return UUID.nameUUIDFromBytes(
        ("cimd:" + clientId).getBytes(StandardCharsets.UTF_8)
    ).toString();
  }

  private static Set<String> normalizedValues(
      final Object raw,
      final Set<String> fallback
  ) {
    List<String> values = stringList(raw);
    return values.isEmpty() ? fallback : new LinkedHashSet<>(values);
  }

  private static List<String> stringList(final Object raw) {
    if (!(raw instanceof List<?> values)) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (Object value : values) {
      if (!(value instanceof String text) || !StringUtils.hasText(text)) {
        throw new IllegalArgumentException("Client metadata contains a non-string value");
      }
      result.add(text.trim());
    }
    return result.stream().distinct().toList();
  }

  private static String requiredString(
      final Map<String, Object> metadata,
      final String key
  ) {
    String value = string(metadata, key);
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException("Client metadata field is required: " + key);
    }
    return value.trim();
  }

  private static String string(final Map<String, Object> metadata, final String key) {
    Object value = metadata.get(key);
    return value instanceof String text ? text : null;
  }

  private static String normalized(final String value, final String fallback) {
    return StringUtils.hasText(value) ? value.trim() : fallback;
  }

  private static String safeOrigin(final String value) {
    try {
      URI uri = URI.create(value);
      return uri.getScheme() + "://" + uri.getHost();
    } catch (IllegalArgumentException ex) {
      return "invalid client URL";
    }
  }

  private static Duration positive(final Duration value, final Duration fallback) {
    return value == null || value.isZero() || value.isNegative() ? fallback : value;
  }

  private static int positive(final int value, final int fallback) {
    return value > 0 ? value : fallback;
  }

  private record CacheEntry(RegisteredClient registeredClient, Instant expiresAt) {
  }
}
