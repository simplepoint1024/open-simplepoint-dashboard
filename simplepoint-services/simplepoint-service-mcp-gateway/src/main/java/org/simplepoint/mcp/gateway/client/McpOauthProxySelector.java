package org.simplepoint.mcp.gateway.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Operator-configured outbound proxy selection for OAuth control-plane traffic. */
final class McpOauthProxySelector extends ProxySelector {

  private final Proxy proxy;

  private final List<String> noProxy;

  private McpOauthProxySelector(final Proxy proxy, final List<String> noProxy) {
    this.proxy = proxy;
    this.noProxy = noProxy;
  }

  static Optional<ProxySelector> create(
      final String proxyUrl,
      final String noProxy
  ) {
    if (proxyUrl == null || proxyUrl.isBlank()) {
      return Optional.empty();
    }
    URI uri;
    try {
      uri = URI.create(proxyUrl.trim());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("OAuth proxy URL is invalid", ex);
    }
    if (!"http".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || (uri.getPath() != null && !uri.getPath().isEmpty()
        && !"/".equals(uri.getPath()))) {
      throw new IllegalArgumentException(
          "OAuth proxy URL must be an HTTP origin without credentials"
      );
    }
    int port = uri.getPort() < 0 ? 80 : uri.getPort();
    Proxy proxy = new Proxy(
        Proxy.Type.HTTP,
        InetSocketAddress.createUnresolved(uri.getHost(), port)
    );
    List<String> exclusions = noProxy == null || noProxy.isBlank()
        ? List.of()
        : java.util.Arrays.stream(noProxy.split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(value -> value.toLowerCase(Locale.ROOT))
            .toList();
    return Optional.of(new McpOauthProxySelector(proxy, exclusions));
  }

  @Override
  public List<Proxy> select(final URI uri) {
    if (uri == null || bypass(uri.getHost(), uri.getPort())) {
      return List.of(Proxy.NO_PROXY);
    }
    return List.of(proxy);
  }

  @Override
  public void connectFailed(
      final URI uri,
      final SocketAddress socketAddress,
      final IOException exception
  ) {
    // HttpClient reports the original failure; no mutable proxy health is kept here.
  }

  private boolean bypass(final String value, final int port) {
    if (value == null) {
      return true;
    }
    String host = value.toLowerCase(Locale.ROOT);
    if (isLoopback(host)) {
      return true;
    }
    for (String configured : noProxy) {
      if ("*".equals(configured)) {
        return true;
      }
      String candidate = withoutPort(configured, port);
      if (candidate == null) {
        continue;
      }
      if (candidate.startsWith("*.")) {
        candidate = candidate.substring(1);
      }
      if (candidate.startsWith(".")) {
        if (host.endsWith(candidate)) {
          return true;
        }
      } else if (host.equals(candidate) || host.endsWith("." + candidate)) {
        return true;
      }
    }
    return false;
  }

  private static String withoutPort(final String value, final int targetPort) {
    if (value.startsWith("[")) {
      int close = value.indexOf(']');
      if (close < 0) {
        return value;
      }
      if (close + 1 < value.length()) {
        if (value.charAt(close + 1) != ':'
            || !portMatches(value.substring(close + 2), targetPort)) {
          return null;
        }
      }
      return value.substring(1, close);
    }
    int colon = value.lastIndexOf(':');
    if (colon > 0 && value.indexOf(':') == colon) {
      String suffix = value.substring(colon + 1);
      if (suffix.chars().allMatch(Character::isDigit)) {
        return portMatches(suffix, targetPort) ? value.substring(0, colon) : null;
      }
    }
    return value;
  }

  private static boolean portMatches(final String value, final int targetPort) {
    try {
      return Integer.parseInt(value) == targetPort;
    } catch (NumberFormatException ex) {
      return false;
    }
  }

  private static boolean isLoopback(final String host) {
    return "localhost".equals(host)
        || host.endsWith(".localhost")
        || "::1".equals(host)
        || host.startsWith("127.");
  }
}
