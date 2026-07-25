package org.simplepoint.plugin.oidc.service.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/** Validates identity-provider destinations and blocks SSRF to private networks by default. */
public final class ExternalIdentityProviderUrlValidator {

  private ExternalIdentityProviderUrlValidator() {
  }

  /**
   * Validates a provider URL before it is persisted or contacted.
   *
   * @param value URL
   * @param label field label for errors
   * @param allowPrivateNetwork whether private destinations are explicitly allowed
   * @param resolveHost whether DNS destinations should be resolved and checked
   */
  public static void validate(
      final String value,
      final String label,
      final boolean allowPrivateNetwork,
      final boolean resolveHost
  ) {
    if (value == null || value.isBlank()) {
      return;
    }
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException(label + " 不是有效 URL", ex);
    }
    String scheme = uri.getScheme() == null
        ? ""
        : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!"https".equals(scheme) && !(allowPrivateNetwork && "http".equals(scheme))) {
      throw new IllegalArgumentException(label + " 必须使用 HTTPS");
    }
    if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
      throw new IllegalArgumentException(label + " 格式不安全");
    }
    if (allowPrivateNetwork || !resolveHost) {
      return;
    }
    try {
      for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
        if (isRestrictedAddress(address)) {
          throw new IllegalArgumentException(label + " 不允许访问本机或私有网络地址");
        }
      }
    } catch (UnknownHostException ex) {
      throw new IllegalArgumentException(label + " 域名无法解析", ex);
    }
  }

  static boolean isRestrictedAddress(final InetAddress address) {
    byte[] bytes = address.getAddress();
    if (address.isAnyLocalAddress() || address.isLoopbackAddress()
        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return true;
    }
    if (bytes.length == 4) {
      int first = Byte.toUnsignedInt(bytes[0]);
      int second = Byte.toUnsignedInt(bytes[1]);
      return first == 0
          || first == 10
          || first == 127
          || (first == 100 && second >= 64 && second <= 127)
          || (first == 169 && second == 254)
          || (first == 172 && second >= 16 && second <= 31)
          || (first == 192 && second == 168)
          || (first == 198 && (second == 18 || second == 19))
          || first >= 224;
    }
    return false;
  }
}
