package org.simplepoint.plugin.ai.core.service.adapter;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import org.simplepoint.plugin.ai.core.api.exception.AiProviderRequestException;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;

/**
 * Shared HTTP behavior for provider adapters.
 */
final class ProviderHttpSupport {

  private final HttpClient httpClient;

  private final long maxResponseBytes;

  private final long maxStreamBytes;

  private final int maxStreamLineCharacters;

  ProviderHttpSupport(final AiProperties properties) {
    int timeout = positive(properties.getConnectTimeoutSeconds(), 10);
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(timeout))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    this.maxResponseBytes = positive(properties.getProviderMaxResponseBytes(), 10L * 1024L * 1024L);
    this.maxStreamBytes = positive(properties.getProviderMaxStreamBytes(), 20L * 1024L * 1024L);
    this.maxStreamLineCharacters = positive(
        properties.getProviderMaxStreamLineCharacters(),
        1_048_576
    );
  }

  String send(final HttpRequest request, final boolean allowPrivateNetwork) {
    validateDestination(request.uri(), allowPrivateNetwork);
    try {
      HttpResponse<InputStream> response = httpClient.send(
          request,
          HttpResponse.BodyHandlers.ofInputStream()
      );
      try (InputStream input = response.body()) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
          throw new AiProviderRequestException(
              response.statusCode(),
              "供应商接口返回 HTTP " + response.statusCode() + ": "
                  + truncate(readErrorBody(input))
          );
        }
        return new String(readLimited(input, maxResponseBytes), StandardCharsets.UTF_8);
      }
    } catch (ResponseLimitExceededException ex) {
      throw responseLimitException(ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("调用供应商接口时线程被中断", ex);
    } catch (IOException ex) {
      throw new IllegalStateException("无法连接供应商接口: " + ex.getMessage(), ex);
    }
  }

  void stream(
      final HttpRequest request,
      final boolean allowPrivateNetwork,
      final Consumer<String> lineConsumer,
      final AiStreamCancellation cancellation
  ) {
    validateDestination(request.uri(), allowPrivateNetwork);
    cancellation.throwIfCancelled();
    CompletableFuture<HttpResponse<InputStream>> requestFuture = httpClient.sendAsync(
        request,
        HttpResponse.BodyHandlers.ofInputStream()
    );
    cancellation.registerRequest(requestFuture);
    HttpResponse<InputStream> response;
    try {
      response = requestFuture.join();
    } catch (CancellationException ex) {
      throw ex;
    } catch (CompletionException ex) {
      throw connectionException(ex.getCause());
    } finally {
      cancellation.releaseRequest(requestFuture);
    }
    InputStream input = response.body();
    cancellation.registerResponseBody(input);
    try (input) {
      cancellation.throwIfCancelled();
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new AiProviderRequestException(
            response.statusCode(),
            "供应商接口返回 HTTP " + response.statusCode() + ": "
                + truncate(readErrorBody(input))
        );
      }
      readStreamLines(
          new LimitedInputStream(input, maxStreamBytes),
          maxStreamLineCharacters,
          line -> {
            cancellation.throwIfCancelled();
            lineConsumer.accept(line);
          }
      );
      cancellation.throwIfCancelled();
    } catch (ResponseLimitExceededException ex) {
      throw responseLimitException(ex);
    } catch (IOException ex) {
      if (cancellation.isCancelled()) {
        throw new CancellationException("AI 流式调用已取消");
      }
      throw new IllegalStateException("无法连接供应商接口: " + ex.getMessage(), ex);
    } finally {
      cancellation.releaseResponseBody(input);
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

  static long positive(final Long value, final long fallback) {
    return value != null && value > 0 ? value : fallback;
  }

  private static RuntimeException connectionException(final Throwable cause) {
    if (cause instanceof CancellationException cancellation) {
      return cancellation;
    }
    if (cause instanceof RuntimeException runtimeException) {
      return runtimeException;
    }
    return new IllegalStateException(
        "无法连接供应商接口: " + (cause == null ? "未知错误" : cause.getMessage()),
        cause
    );
  }

  private static void readStreamLines(
      final InputStream input,
      final int maxLineCharacters,
      final Consumer<String> lineConsumer
  ) throws IOException {
    InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
    char[] buffer = new char[8192];
    StringBuilder line = new StringBuilder(Math.min(maxLineCharacters, 8192));
    int read;
    while ((read = reader.read(buffer)) >= 0) {
      for (int index = 0; index < read; index++) {
        char value = buffer[index];
        if (value == '\n') {
          emitLine(line, lineConsumer);
          continue;
        }
        line.append(value);
        if (line.length() > maxLineCharacters) {
          throw new ResponseLimitExceededException(
              "供应商流式响应单行超过 " + maxLineCharacters + " 个字符"
          );
        }
      }
    }
    if (!line.isEmpty()) {
      emitLine(line, lineConsumer);
    }
  }

  private static void emitLine(
      final StringBuilder line,
      final Consumer<String> lineConsumer
  ) {
    int length = line.length();
    if (length > 0 && line.charAt(length - 1) == '\r') {
      line.setLength(length - 1);
    }
    lineConsumer.accept(line.toString());
    line.setLength(0);
  }

  private static byte[] readLimited(final InputStream input, final long limit) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(limit, 8192L));
    byte[] buffer = new byte[8192];
    long total = 0;
    int read;
    while ((read = input.read(buffer)) >= 0) {
      total += read;
      if (total > limit) {
        throw new ResponseLimitExceededException("供应商响应超过 " + limit + " 字节");
      }
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }

  private static AiProviderRequestException responseLimitException(
      final ResponseLimitExceededException ex
  ) {
    return new AiProviderRequestException(502, ex.getMessage());
  }

  private static String truncate(final String body) {
    if (body == null || body.isBlank()) {
      return "无响应内容";
    }
    String compact = body.replaceAll("\\s+", " ").trim();
    return compact.length() <= 500 ? compact : compact.substring(0, 500);
  }

  private static String readErrorBody(final InputStream input) throws IOException {
    BufferedReader reader = new BufferedReader(
        new InputStreamReader(input, StandardCharsets.UTF_8)
    );
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

  private static final class LimitedInputStream extends FilterInputStream {

    private final long limit;

    private long count;

    private LimitedInputStream(final InputStream input, final long limit) {
      super(input);
      this.limit = limit;
    }

    @Override
    public int read() throws IOException {
      int value = super.read();
      if (value >= 0 && ++count > limit) {
        throw new ResponseLimitExceededException("供应商流式响应超过 " + limit + " 字节");
      }
      return value;
    }

    @Override
    public int read(final byte[] buffer, final int offset, final int length) throws IOException {
      int allowed = (int) Math.min(length, Math.max(1L, limit - count + 1L));
      int read = super.read(buffer, offset, allowed);
      if (read > 0 && (count += read) > limit) {
        throw new ResponseLimitExceededException("供应商流式响应超过 " + limit + " 字节");
      }
      return read;
    }
  }

  private static final class ResponseLimitExceededException extends IOException {

    private ResponseLimitExceededException(final String message) {
      super(message);
    }
  }
}
