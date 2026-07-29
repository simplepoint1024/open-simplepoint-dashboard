package org.simplepoint.mcp.gateway.client;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Validates remote MCP destinations before any network connection is opened.
 */
@Component
public class McpEndpointPolicy {

  /**
   * Validates and splits a Streamable HTTP endpoint into SDK base URI and endpoint path.
   *
   * @param value endpoint URL
   * @param allowPrivateNetwork whether private destinations are permitted
   * @return normalized endpoint
   */
  public Endpoint validate(
      final String value,
      final boolean allowPrivateNetwork
  ) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("MCP endpoint must not be blank");
    }
    URI uri;
    try {
      uri = URI.create(value.trim());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("MCP endpoint is not a valid URI", ex);
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if ((!"http".equals(scheme) && !"https".equals(scheme))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) {
      throw new IllegalArgumentException(
          "MCP endpoint must be an absolute http or https URI without credentials, query, or fragment"
      );
    }
    validateAddresses(uri.getHost(), allowPrivateNetwork);
    String endpointPath = uri.getRawPath();
    if (endpointPath == null || endpointPath.isBlank() || "/".equals(endpointPath)) {
      endpointPath = "/mcp";
    }
    try {
      URI base = new URI(
          scheme,
          null,
          uri.getHost(),
          uri.getPort(),
          null,
          null,
          null
      );
      return new Endpoint(base.toString(), endpointPath);
    } catch (URISyntaxException ex) {
      throw new IllegalArgumentException("MCP endpoint authority is invalid", ex);
    }
  }

  /**
   * Validates an OAuth metadata, registration, or token endpoint.
   *
   * @param value endpoint URL
   * @param allowPrivateNetwork whether private destinations are permitted
   * @param allowInsecureHttp whether HTTP is explicitly enabled for local integration tests
   * @return normalized URI
   */
  public URI validateOauthUri(
      final String value,
      final boolean allowPrivateNetwork,
      final boolean allowInsecureHttp
  ) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("OAuth endpoint must not be blank");
    }
    URI uri;
    try {
      uri = URI.create(value.trim());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("OAuth endpoint is not a valid URI", ex);
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if ((!"https".equals(scheme) && !(allowInsecureHttp && "http".equals(scheme)))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getFragment() != null) {
      throw new IllegalArgumentException(
          "OAuth endpoints must be absolute HTTPS URIs without credentials or fragments"
      );
    }
    validateAddresses(uri.getHost(), allowPrivateNetwork);
    return uri.normalize();
  }

  private static void validateAddresses(
      final String host,
      final boolean allowPrivateNetwork
  ) {
    if (allowPrivateNetwork) {
      return;
    }
    if ("localhost".equalsIgnoreCase(host)
        || host.toLowerCase(Locale.ROOT).endsWith(".localhost")) {
      throw new IllegalArgumentException("MCP endpoint must not resolve to localhost");
    }
    try {
      InetAddress[] addresses = InetAddress.getAllByName(host);
      if (addresses.length == 0) {
        throw new IllegalArgumentException("MCP endpoint host cannot be resolved");
      }
      for (InetAddress address : addresses) {
        if (isRestrictedAddress(address)) {
          throw new IllegalArgumentException(
              "MCP endpoint resolves to a restricted network: " + address.getHostAddress()
          );
        }
      }
    } catch (UnknownHostException ex) {
      throw new IllegalArgumentException("MCP endpoint host cannot be resolved: " + host, ex);
    }
  }

  static boolean isRestrictedAddress(final InetAddress address) {
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return true;
    }
    byte[] bytes = address.getAddress();
    if (bytes.length == 4) {
      int first = Byte.toUnsignedInt(bytes[0]);
      int second = Byte.toUnsignedInt(bytes[1]);
      return first == 0
          || (first == 100 && second >= 64 && second <= 127)
          || (first == 192 && second == 0)
          || (first == 198 && (second == 18 || second == 19))
          || first >= 240;
    }
    int first = Byte.toUnsignedInt(bytes[0]);
    return (first & 0xfe) == 0xfc;
  }

  /**
   * Normalized MCP endpoint parts.
   *
   * @param baseUri scheme and authority
   * @param endpointPath MCP endpoint path
   */
  public record Endpoint(String baseUri, String endpointPath) {
  }
}
