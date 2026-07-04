package com.simplepoint.service.router.invocation;

import com.simplepoint.service.router.config.ServiceRouterProperties;
import com.simplepoint.service.router.exception.ServiceRouterException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * OAuth2 client_credentials access token provider for service-to-service routing.
 */
public class ClientCredentialsServiceRouterAccessTokenProvider implements ServiceRouterAccessTokenProvider {

  private final RestClient restClient;

  private final ServiceRouterProperties.InternalAuth.Oauth2 properties;

  private final Clock clock;

  private String cachedToken;

  private Instant expiresAt = Instant.EPOCH;

  /**
   * Creates a client credentials token provider.
   *
   * @param restClient HTTP client used for the token endpoint
   * @param properties OAuth2 properties
   */
  public ClientCredentialsServiceRouterAccessTokenProvider(
      final RestClient restClient,
      final ServiceRouterProperties.InternalAuth.Oauth2 properties
  ) {
    this(restClient, properties, Clock.systemUTC());
  }

  ClientCredentialsServiceRouterAccessTokenProvider(
      final RestClient restClient,
      final ServiceRouterProperties.InternalAuth.Oauth2 properties,
      final Clock clock
  ) {
    this.restClient = restClient;
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  public synchronized String getAccessToken() {
    if (StringUtils.hasText(cachedToken) && Instant.now(clock).isBefore(expiresAt)) {
      return cachedToken;
    }
    TokenResponse tokenResponse = requestAccessToken();
    cachedToken = tokenResponse.accessToken();
    expiresAt = resolveExpiresAt(tokenResponse.expiresIn());
    return cachedToken;
  }

  @SuppressWarnings("unchecked")
  private TokenResponse requestAccessToken() {
    validateProperties();
    LinkedMultiValueMap<String, String> body = new LinkedMultiValueMap<>();
    body.add("grant_type", "client_credentials");
    String scope = scopeValue(properties.getScopes());
    if (StringUtils.hasText(scope)) {
      body.add("scope", scope);
    }
    Map<String, Object> response = restClient.post()
        .uri(properties.getTokenUri())
        .header(HttpHeaders.AUTHORIZATION, basicAuthorization())
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(body)
        .retrieve()
        .body(Map.class);
    if (response == null || !StringUtils.hasText((String) response.get("access_token"))) {
      throw new ServiceRouterException("OAuth2 token endpoint did not return an access token.");
    }
    return new TokenResponse(
        (String) response.get("access_token"),
        expiresIn(response.get("expires_in"))
    );
  }

  private void validateProperties() {
    if (!StringUtils.hasText(properties.getTokenUri())) {
      throw new ServiceRouterException("Service-router OAuth2 token-uri must be configured.");
    }
    if (!StringUtils.hasText(properties.getClientId())) {
      throw new ServiceRouterException("Service-router OAuth2 client-id must be configured.");
    }
    if (!StringUtils.hasText(properties.getClientSecret())) {
      throw new ServiceRouterException("Service-router OAuth2 client-secret must be configured.");
    }
  }

  private String basicAuthorization() {
    String credentials = properties.getClientId() + ":" + properties.getClientSecret();
    return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
  }

  private Instant resolveExpiresAt(final long expiresIn) {
    long skewSeconds = properties.getTokenRefreshSkew() == null
        ? 0
        : properties.getTokenRefreshSkew().toSeconds();
    long cacheSeconds = Math.max(1, expiresIn - Math.max(0, skewSeconds));
    return Instant.now(clock).plusSeconds(cacheSeconds);
  }

  private static long expiresIn(final Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String text && StringUtils.hasText(text)) {
      return Long.parseLong(text);
    }
    return 300;
  }

  private static String scopeValue(final List<String> scopes) {
    if (scopes == null || scopes.isEmpty()) {
      return null;
    }
    return String.join(" ", scopes.stream()
        .filter(StringUtils::hasText)
        .toList());
  }

  private record TokenResponse(String accessToken, long expiresIn) {
  }
}
