package org.simplepoint.mcp.gateway.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.mcp.gateway.config.McpGatewayProperties;
import org.simplepoint.mcp.gateway.security.McpOauthClientMetadataDocument;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthDiscoveryRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthDiscoveryResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthRegistrationRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthRegistrationResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthTokenRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthTokenResult;
import org.springframework.stereotype.Component;

/**
 * OAuth 2.1 protocol client for protected remote MCP resources.
 */
@Component
public class RemoteMcpOauthClient {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
  };

  private final McpEndpointPolicy endpointPolicy;

  private final McpGatewayProperties properties;

  private final ObjectMapper objectMapper;

  private final McpOauthClientMetadataDocument clientMetadataDocument;

  private final HttpClient httpClient;

  /**
   * Creates the OAuth protocol client.
   */
  public RemoteMcpOauthClient(
      final McpEndpointPolicy endpointPolicy,
      final McpGatewayProperties properties,
      final ObjectMapper objectMapper,
      final McpOauthClientMetadataDocument clientMetadataDocument
  ) {
    this.endpointPolicy = endpointPolicy;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.clientMetadataDocument = clientMetadataDocument;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(positive(properties.getConnectTimeout(), Duration.ofSeconds(10)))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  /**
   * Discovers RFC 9728 protected-resource metadata and authorization server metadata.
   */
  public McpGatewayOauthDiscoveryResult discover(
      final McpGatewayOauthDiscoveryRequest request
  ) {
    if (request == null) {
      throw new IllegalArgumentException("OAuth discovery request must not be null");
    }
    URI resource = oauthUri(request.endpointUrl(), request.allowPrivateNetwork());
    Map<String, Object> resourceMetadata = firstMetadata(
        protectedResourceMetadataUris(resource),
        request.allowPrivateNetwork(),
        "protected resource metadata"
    );
    String advertisedResource = requiredString(resourceMetadata, "resource");
    if (!canonicalResource(resource).equals(canonicalResource(
        oauthUri(advertisedResource, request.allowPrivateNetwork())
    ))) {
      throw new IllegalArgumentException(
          "Protected resource metadata does not identify the requested MCP resource"
      );
    }
    List<String> authorizationServers = stringList(
        resourceMetadata.get("authorization_servers")
    );
    if (authorizationServers.isEmpty()) {
      throw new IllegalArgumentException(
          "Protected resource metadata must advertise an authorization server"
      );
    }
    URI issuer = oauthUri(authorizationServers.getFirst(), request.allowPrivateNetwork());
    Map<String, Object> serverMetadata = firstMetadata(
        authorizationServerMetadataUris(issuer),
        request.allowPrivateNetwork(),
        "authorization server metadata"
    );
    String metadataIssuer = requiredString(serverMetadata, "issuer");
    if (!canonicalIssuer(issuer).equals(canonicalIssuer(
        oauthUri(metadataIssuer, request.allowPrivateNetwork())
    ))) {
      throw new IllegalArgumentException("Authorization server metadata issuer mismatch");
    }
    String authorizationEndpoint = validatedMetadataEndpoint(
        serverMetadata,
        "authorization_endpoint",
        request.allowPrivateNetwork()
    );
    String tokenEndpoint = validatedMetadataEndpoint(
        serverMetadata,
        "token_endpoint",
        request.allowPrivateNetwork()
    );
    String registrationEndpoint = optionalValidatedMetadataEndpoint(
        serverMetadata,
        "registration_endpoint",
        request.allowPrivateNetwork()
    );
    List<String> pkceMethods = stringList(
        serverMetadata.get("code_challenge_methods_supported")
    );
    if (!pkceMethods.contains("S256")) {
      throw new IllegalArgumentException(
          "Authorization server does not advertise the required PKCE S256 method"
      );
    }
    return new McpGatewayOauthDiscoveryResult(
        advertisedResource,
        issuer.toString(),
        authorizationEndpoint,
        tokenEndpoint,
        registrationEndpoint,
        booleanValue(
            serverMetadata.get("client_id_metadata_document_supported")
        ),
        clientMetadataDocument.configured()
            ? clientMetadataDocument.clientId() : null,
        clientMetadataDocument.configured()
            ? clientMetadataDocument.redirectUris() : List.of(),
        stringList(resourceMetadata.get("scopes_supported")),
        pkceMethods,
        Map.copyOf(resourceMetadata),
        Map.copyOf(serverMetadata)
    );
  }

  /**
   * Performs RFC 7591 dynamic client registration.
   */
  public McpGatewayOauthRegistrationResult register(
      final McpGatewayOauthRegistrationRequest request
  ) {
    if (request == null || request.redirectUris() == null
        || request.redirectUris().isEmpty()) {
      throw new IllegalArgumentException("OAuth redirect URI must be configured");
    }
    final URI endpoint = oauthUri(
        request.registrationEndpoint(),
        request.allowPrivateNetwork()
    );
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("redirect_uris", request.redirectUris());
    body.put("client_name", normalized(request.clientName(), "Open SimplePoint MCP Gateway"));
    body.put("grant_types", List.of("authorization_code", "refresh_token"));
    body.put("response_types", List.of("code"));
    body.put("token_endpoint_auth_method", "none");
    if (request.scopes() != null && !request.scopes().isEmpty()) {
      body.put("scope", String.join(" ", request.scopes()));
    }
    Map<String, Object> result = sendJson(endpoint, body, request.allowPrivateNetwork());
    return new McpGatewayOauthRegistrationResult(
        requiredString(result, "client_id"),
        optionalString(result, "client_secret"),
        longValue(result.get("client_secret_expires_at")),
        normalized(optionalString(result, "token_endpoint_auth_method"), "none")
    );
  }

  /**
   * Exchanges an authorization code or refresh token at the validated token endpoint.
   */
  public McpGatewayOauthTokenResult exchange(final McpGatewayOauthTokenRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("OAuth token request must not be null");
    }
    final URI endpoint = oauthUri(
        request.tokenEndpoint(),
        request.allowPrivateNetwork()
    );
    String grantType = required(request.grantType(), "OAuth grant type must not be blank");
    if (!Set.of("authorization_code", "refresh_token").contains(grantType)) {
      throw new IllegalArgumentException("Unsupported OAuth grant type");
    }
    String clientId = required(request.clientId(), "OAuth client ID must not be blank");
    final String authMethod = normalized(request.tokenEndpointAuthMethod(), "none");
    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", grantType);
    form.put("client_id", clientId);
    if ("authorization_code".equals(grantType)) {
      form.put("code", required(request.code(), "OAuth authorization code is missing"));
      form.put("code_verifier", required(
          request.codeVerifier(),
          "OAuth PKCE verifier is missing"
      ));
      form.put("redirect_uri", required(
          request.redirectUri(),
          "OAuth redirect URI is missing"
      ));
    } else {
      form.put("refresh_token", required(
          request.refreshToken(),
          "OAuth refresh token is missing"
      ));
      if (request.scopes() != null && !request.scopes().isBlank()) {
        form.put("scope", request.scopes().trim());
      }
    }
    form.put("resource", required(request.resource(), "OAuth target resource is missing"));
    String authorization = null;
    if ("client_secret_post".equals(authMethod)) {
      form.put("client_secret", required(
          request.clientSecret(),
          "OAuth client secret is missing"
      ));
    } else if ("client_secret_basic".equals(authMethod)) {
      String credentials = clientId + ":" + required(
          request.clientSecret(),
          "OAuth client secret is missing"
      );
      authorization = "Basic " + java.util.Base64.getEncoder()
          .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    } else if (!"none".equals(authMethod)) {
      throw new IllegalArgumentException("Unsupported token endpoint authentication method");
    }
    Map<String, Object> result = sendForm(
        endpoint,
        form,
        authorization,
        request.allowPrivateNetwork()
    );
    String tokenType = requiredString(result, "token_type");
    if (!"Bearer".equalsIgnoreCase(tokenType)) {
      throw new IllegalArgumentException("Remote MCP server returned a non-Bearer access token");
    }
    return new McpGatewayOauthTokenResult(
        requiredString(result, "access_token"),
        "Bearer",
        longValue(result.get("expires_in")),
        optionalString(result, "refresh_token"),
        optionalString(result, "scope")
    );
  }

  private Map<String, Object> firstMetadata(
      final List<URI> candidates,
      final boolean allowPrivateNetwork,
      final String label
  ) {
    RuntimeException last = null;
    for (URI candidate : candidates) {
      try {
        return getJson(candidate, allowPrivateNetwork);
      } catch (MetadataNotFoundException ex) {
        last = ex;
      }
    }
    throw new IllegalArgumentException("Unable to discover " + label, last);
  }

  private Map<String, Object> getJson(
      final URI uri,
      final boolean allowPrivateNetwork
  ) {
    URI endpoint = oauthUri(uri.toString(), allowPrivateNetwork);
    HttpRequest request = HttpRequest.newBuilder(endpoint)
        .timeout(positive(properties.getRequestTimeout(), Duration.ofSeconds(30)))
        .header("Accept", "application/json")
        .GET()
        .build();
    HttpResponse<byte[]> response = send(request);
    if (response.statusCode() == 404) {
      throw new MetadataNotFoundException();
    }
    requireSuccess(response, "OAuth metadata discovery");
    return parseJson(response.body(), "OAuth metadata");
  }

  private Map<String, Object> sendJson(
      final URI endpoint,
      final Map<String, Object> body,
      final boolean allowPrivateNetwork
  ) {
    oauthUri(endpoint.toString(), allowPrivateNetwork);
    try {
      HttpRequest request = HttpRequest.newBuilder(endpoint)
          .timeout(positive(properties.getRequestTimeout(), Duration.ofSeconds(30)))
          .header("Accept", "application/json")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body)))
          .build();
      HttpResponse<byte[]> response = send(request);
      requireSuccess(response, "OAuth dynamic client registration");
      return parseJson(response.body(), "OAuth registration response");
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("OAuth registration request is not valid JSON", ex);
    }
  }

  private Map<String, Object> sendForm(
      final URI endpoint,
      final Map<String, String> form,
      final String authorization,
      final boolean allowPrivateNetwork
  ) {
    oauthUri(endpoint.toString(), allowPrivateNetwork);
    HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
        .timeout(positive(properties.getRequestTimeout(), Duration.ofSeconds(30)))
        .header("Accept", "application/json")
        .header("Content-Type", "application/x-www-form-urlencoded");
    if (authorization != null) {
      builder.header("Authorization", authorization);
    }
    String encoded = form.entrySet().stream()
        .map(entry -> formEncode(entry.getKey()) + "=" + formEncode(entry.getValue()))
        .collect(java.util.stream.Collectors.joining("&"));
    HttpResponse<byte[]> response = send(
        builder.POST(HttpRequest.BodyPublishers.ofString(encoded)).build()
    );
    requireSuccess(response, "OAuth token exchange");
    return parseJson(response.body(), "OAuth token response");
  }

  private HttpResponse<byte[]> send(final HttpRequest request) {
    try {
      HttpResponse<byte[]> response = httpClient.send(
          request,
          HttpResponse.BodyHandlers.ofByteArray()
      );
      long limit = Math.max(1, properties.getMaxOauthResponseBytes());
      if (response.body().length > limit) {
        throw new IllegalArgumentException("OAuth response exceeds " + limit + " bytes");
      }
      return response;
    } catch (IOException ex) {
      throw new IllegalStateException("OAuth endpoint request failed", ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("OAuth endpoint request was interrupted", ex);
    }
  }

  private void requireSuccess(
      final HttpResponse<byte[]> response,
      final String operation
  ) {
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IllegalArgumentException(
          operation + " failed with HTTP " + response.statusCode()
      );
    }
  }

  private Map<String, Object> parseJson(final byte[] value, final String label) {
    try {
      return objectMapper.readValue(value, MAP_TYPE);
    } catch (IOException ex) {
      throw new IllegalArgumentException(label + " is not valid JSON", ex);
    }
  }

  private String validatedMetadataEndpoint(
      final Map<String, Object> metadata,
      final String field,
      final boolean allowPrivateNetwork
  ) {
    return oauthUri(requiredString(metadata, field), allowPrivateNetwork).toString();
  }

  private String optionalValidatedMetadataEndpoint(
      final Map<String, Object> metadata,
      final String field,
      final boolean allowPrivateNetwork
  ) {
    String value = optionalString(metadata, field);
    return value == null ? null : oauthUri(value, allowPrivateNetwork).toString();
  }

  private URI oauthUri(final String value, final boolean allowPrivateNetwork) {
    return endpointPolicy.validateOauthUri(
        value,
        allowPrivateNetwork,
        properties.isAllowInsecureOauthEndpoints()
    );
  }

  private static List<URI> protectedResourceMetadataUris(final URI resource) {
    String origin = origin(resource);
    String path = resource.getRawPath();
    List<URI> candidates = new ArrayList<>();
    if (path != null && !path.isBlank() && !"/".equals(path)) {
      candidates.add(URI.create(
          origin + "/.well-known/oauth-protected-resource" + normalizePath(path)
      ));
    }
    candidates.add(URI.create(origin + "/.well-known/oauth-protected-resource"));
    return candidates;
  }

  private static List<URI> authorizationServerMetadataUris(final URI issuer) {
    String origin = origin(issuer);
    String path = issuer.getRawPath();
    Set<URI> candidates = new LinkedHashSet<>();
    if (path != null && !path.isBlank() && !"/".equals(path)) {
      String normalizedPath = normalizePath(path);
      candidates.add(URI.create(
          origin + "/.well-known/oauth-authorization-server" + normalizedPath
      ));
      candidates.add(URI.create(
          origin + normalizedPath + "/.well-known/openid-configuration"
      ));
      candidates.add(URI.create(
          origin + "/.well-known/openid-configuration" + normalizedPath
      ));
    } else {
      candidates.add(URI.create(origin + "/.well-known/oauth-authorization-server"));
      candidates.add(URI.create(origin + "/.well-known/openid-configuration"));
    }
    return List.copyOf(candidates);
  }

  private static String canonicalResource(final URI value) {
    return value.normalize().toString().replaceAll("/+$", "");
  }

  private static String canonicalIssuer(final URI value) {
    return value.normalize().toString().replaceAll("/+$", "");
  }

  private static String origin(final URI value) {
    int port = value.getPort();
    return value.getScheme() + "://" + value.getHost()
        + (port < 0 ? "" : ":" + port);
  }

  private static String normalizePath(final String value) {
    String path = value.startsWith("/") ? value : "/" + value;
    return path.replaceAll("/+$", "");
  }

  private static String requiredString(
      final Map<String, Object> value,
      final String field
  ) {
    Object raw = value.get(field);
    return required(raw instanceof String text ? text : null,
        "OAuth metadata field is missing: " + field);
  }

  private static String optionalString(
      final Map<String, Object> value,
      final String field
  ) {
    Object raw = value.get(field);
    if (!(raw instanceof String text) || text.isBlank()) {
      return null;
    }
    return text.trim();
  }

  private static List<String> stringList(final Object value) {
    if (!(value instanceof List<?> values)) {
      return List.of();
    }
    return values.stream()
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .map(String::trim)
        .filter(item -> !item.isBlank())
        .distinct()
        .toList();
  }

  private static long longValue(final Object value) {
    if (value instanceof Number number) {
      return Math.max(0, number.longValue());
    }
    return 0;
  }

  private static boolean booleanValue(final Object value) {
    return value instanceof Boolean supported && supported;
  }

  private static String required(final String value, final String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
    return value.trim();
  }

  private static String normalized(final String value, final String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static String formEncode(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8)
        .replace("+", "%20");
  }

  private static Duration positive(final Duration value, final Duration fallback) {
    return value == null || value.isZero() || value.isNegative() ? fallback : value;
  }

  private static final class MetadataNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;
  }
}
