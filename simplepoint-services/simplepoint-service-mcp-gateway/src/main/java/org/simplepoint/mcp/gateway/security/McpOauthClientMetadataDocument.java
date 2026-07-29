package org.simplepoint.mcp.gateway.security;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.simplepoint.mcp.gateway.config.McpGatewayProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Validates and renders the public OAuth Client ID Metadata Document for this Gateway.
 */
@Component
public class McpOauthClientMetadataDocument {

  /**
   * Stable path routed publicly by the Host service.
   */
  public static final String PATH =
      "/.well-known/oauth-client/open-simplepoint-mcp-gateway";

  private final McpGatewayProperties properties;

  /**
   * Creates the metadata document renderer.
   *
   * @param properties Gateway configuration
   */
  public McpOauthClientMetadataDocument(final McpGatewayProperties properties) {
    this.properties = properties;
  }

  /**
   * Returns whether a standards-compliant public document is configured.
   *
   * @return true when metadata can be published
   */
  public boolean configured() {
    try {
      clientId();
      redirectUris();
      return true;
    } catch (IllegalStateException ex) {
      return false;
    }
  }

  /**
   * Returns the exact URL used as the OAuth client identifier.
   *
   * @return client metadata document URL
   */
  public String clientId() {
    String value = properties.getOauthClientMetadataDocumentUri();
    if (!StringUtils.hasText(value) || value.length() > 2048) {
      throw new IllegalStateException("OAuth client metadata document URI is not configured");
    }
    URI uri = uri(value, "OAuth client metadata document URI");
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !PATH.equals(uri.getPath())) {
      throw new IllegalStateException(
          "OAuth client metadata document URI must be the public HTTPS Gateway path"
      );
    }
    return uri.toString();
  }

  /**
   * Returns exact redirect URIs published in the metadata document.
   *
   * @return validated redirect URIs
   */
  public List<String> redirectUris() {
    List<String> values = properties.getOauthClientRedirectUris() == null
        ? List.of() : properties.getOauthClientRedirectUris().stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .distinct()
            .toList();
    if (values.isEmpty() || values.size() > 20) {
      throw new IllegalStateException(
          "OAuth client metadata redirect URIs must contain between 1 and 20 values"
      );
    }
    values.forEach(McpOauthClientMetadataDocument::validateRedirectUri);
    return values;
  }

  /**
   * Renders the JSON document defined by the CIMD specification.
   *
   * @return immutable metadata properties
   */
  public Map<String, Object> document() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("client_id", clientId());
    value.put("client_name", normalized(
        properties.getOauthClientName(),
        "Open SimplePoint MCP Gateway"
    ));
    optionalHttpsUri(properties.getOauthClientUri(), "client_uri")
        .ifPresent(uri -> value.put("client_uri", uri));
    optionalHttpsUri(properties.getOauthClientLogoUri(), "logo_uri")
        .ifPresent(uri -> value.put("logo_uri", uri));
    value.put("redirect_uris", redirectUris());
    value.put("grant_types", List.of("authorization_code", "refresh_token"));
    value.put("response_types", List.of("code"));
    value.put("token_endpoint_auth_method", "none");
    return Map.copyOf(value);
  }

  private static java.util.Optional<String> optionalHttpsUri(
      final String value,
      final String label
  ) {
    if (!StringUtils.hasText(value)) {
      return java.util.Optional.empty();
    }
    URI uri = uri(value.trim(), label);
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getFragment() != null) {
      throw new IllegalStateException(label + " must be an HTTPS URI");
    }
    return java.util.Optional.of(uri.toString());
  }

  private static void validateRedirectUri(final String value) {
    URI uri = uri(value, "OAuth client redirect URI");
    boolean loopback = "http".equalsIgnoreCase(uri.getScheme())
        && loopback(uri.getHost());
    if ((!loopback && !"https".equalsIgnoreCase(uri.getScheme()))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getFragment() != null) {
      throw new IllegalStateException(
          "OAuth client redirect URI must be HTTPS or an HTTP loopback URI"
      );
    }
  }

  private static URI uri(final String value, final String label) {
    try {
      return URI.create(value);
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException(label + " is invalid", ex);
    }
  }

  private static boolean loopback(final String host) {
    if (host == null) {
      return false;
    }
    String value = host.toLowerCase(Locale.ROOT);
    return "localhost".equals(value)
        || value.endsWith(".localhost")
        || "127.0.0.1".equals(value)
        || "::1".equals(value)
        || "[::1]".equals(value);
  }

  private static String normalized(final String value, final String fallback) {
    return StringUtils.hasText(value) ? value.trim() : fallback;
  }
}
