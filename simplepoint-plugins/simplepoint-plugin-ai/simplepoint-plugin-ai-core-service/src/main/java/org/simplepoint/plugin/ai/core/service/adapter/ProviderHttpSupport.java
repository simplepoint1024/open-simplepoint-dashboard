package org.simplepoint.plugin.ai.core.service.adapter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
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
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  HttpResponse<String> send(final HttpRequest request) {
    try {
      HttpResponse<String> response = httpClient.send(
          request,
          HttpResponse.BodyHandlers.ofString()
      );
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
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
}
