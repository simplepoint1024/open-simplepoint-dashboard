package org.simplepoint.plugin.ai.core.service.adapter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Consumer;
import org.simplepoint.plugin.ai.core.api.exception.AiProviderRequestException;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;

/**
 * Shared HTTP behavior for provider adapters.
 */
final class ProviderHttpSupport {

  private final HttpClient httpClient;

  ProviderHttpSupport(final AiProperties properties) {
    int timeout = positive(properties.getConnectTimeoutSeconds(), 10);
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(timeout))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  HttpResponse<String> send(final HttpRequest request, final boolean allowPrivateNetwork) {
    validateDestination(request.uri(), allowPrivateNetwork);
    try {
      HttpResponse<String> response = httpClient.send(
          request,
          HttpResponse.BodyHandlers.ofString()
      );
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new AiProviderRequestException(
            response.statusCode(),
            "供应商接口返回 HTTP " + response.statusCode() + ": " + truncate(response.body())
        );
      }
      return response;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("调用供应商接口时线程被中断", ex);
    } catch (java.io.IOException ex) {
      throw new IllegalStateException("无法连接供应商接口: " + ex.getMessage(), ex);
    }
  }

  void stream(
      final HttpRequest request,
      final boolean allowPrivateNetwork,
      final Consumer<String> lineConsumer
  ) {
    validateDestination(request.uri(), allowPrivateNetwork);
    try {
      HttpResponse<java.io.InputStream> response = httpClient.send(
          request,
          HttpResponse.BodyHandlers.ofInputStream()
      );
      try (InputStream input = response.body();
           BufferedReader reader = new BufferedReader(
               new InputStreamReader(input, StandardCharsets.UTF_8)
           )) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
          throw new AiProviderRequestException(
              response.statusCode(),
              "供应商接口返回 HTTP " + response.statusCode() + ": "
                  + truncate(readErrorBody(reader))
          );
        }
        String line;
        while ((line = reader.readLine()) != null) {
          lineConsumer.accept(line);
        }
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("调用供应商接口时线程被中断", ex);
    } catch (IOException ex) {
      throw new IllegalStateException("无法连接供应商接口: " + ex.getMessage(), ex);
    }
  }

  static void validateDestination(final URI uri, final boolean allowPrivateNetwork) {
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if ((!"http".equals(scheme) && !"https".equals(scheme))
        || uri.getHost() == null || uri.getUserInfo() != null) {
      throw new IllegalArgumentException("供应商地址必须是无用户信息的有效 http 或 https 地址");
    }
    if (allowPrivateNetwork) {
      return;
    }
    String host = uri.getHost();
    if ("localhost".equalsIgnoreCase(host) || host.toLowerCase(Locale.ROOT).endsWith(".localhost")) {
      throw new IllegalArgumentException("供应商地址不允许访问本机或内网地址");
    }
    try {
      InetAddress[] addresses = InetAddress.getAllByName(host);
      if (addresses.length == 0) {
        throw new IllegalArgumentException("供应商地址无法解析");
      }
      for (InetAddress address : addresses) {
        if (isRestrictedAddress(address)) {
          throw new IllegalArgumentException("供应商地址解析到了受限网络: " + address.getHostAddress());
        }
      }
    } catch (UnknownHostException ex) {
      throw new IllegalArgumentException("供应商地址无法解析: " + host, ex);
    }
  }

  static boolean isRestrictedAddress(final InetAddress address) {
    if (address.isAnyLocalAddress() || address.isLoopbackAddress()
        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
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

  static URI endpoint(final String baseUrl, final String pathAndQuery) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("供应商 Base URL 不能为空");
    }
    URI base;
    try {
      base = URI.create(baseUrl.trim());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("供应商 Base URL 格式无效", ex);
    }
    String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(Locale.ROOT);
    if (!"http".equals(scheme) && !"https".equals(scheme)) {
      throw new IllegalArgumentException("供应商 Base URL 只支持 http 或 https");
    }
    if (base.getHost() == null || base.getUserInfo() != null) {
      throw new IllegalArgumentException("供应商 Base URL 必须包含有效主机且不能包含用户信息");
    }
    String normalizedBase = base.toString().replaceAll("/+$", "");
    String normalizedPath = pathAndQuery.startsWith("/") ? pathAndQuery : "/" + pathAndQuery;
    return URI.create(normalizedBase + normalizedPath);
  }

  static Duration timeout(final int seconds) {
    return Duration.ofSeconds(seconds > 0 ? seconds : 30);
  }

  static int positive(final Integer value, final int fallback) {
    return value != null && value > 0 ? value : fallback;
  }

  private static String truncate(final String body) {
    if (body == null || body.isBlank()) {
      return "无响应内容";
    }
    String compact = body.replaceAll("\\s+", " ").trim();
    return compact.length() <= 500 ? compact : compact.substring(0, 500);
  }

  private static String readErrorBody(final BufferedReader reader) throws IOException {
    char[] buffer = new char[501];
    int offset = 0;
    while (offset < buffer.length) {
      int read = reader.read(buffer, offset, buffer.length - offset);
      if (read < 0) {
        break;
      }
      offset += read;
    }
    return new String(buffer, 0, offset);
  }
}
