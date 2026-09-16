package org.simplepoint.plugin.ai.runtime.service.node;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.net.ssl.SSLParameters;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeImageObservation;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProbeReport;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeOperationException;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeWorkloadDispatchRequest;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeWorkloadObservation;
import org.simplepoint.plugin.ai.runtime.api.properties.AiRuntimeProperties;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeNodeOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Bounded HTTP client for a Tool Runtime node's private fenced API.
 */
@Service
public class HttpAiRuntimeNodeOperations implements AiRuntimeNodeOperations {

  private static final int MAXIMUM_RESPONSE_BYTES = 256 * 1024;

  private static final int MAXIMUM_ERROR_MESSAGE_CHARACTERS = 512;

  private static final Pattern IDENTIFIER =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");

  private final AiRuntimeProperties properties;

  private final ObjectMapper objectMapper;

  private final HttpClient httpClient;

  /**
   * Creates the private node client.
   */
  public HttpAiRuntimeNodeOperations(
      final AiRuntimeProperties properties,
      final ObjectMapper objectMapper
  ) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    HttpClient.Builder builder = HttpClient.newBuilder()
        .connectTimeout(dispatchTimeout())
        .followRedirects(HttpClient.Redirect.NEVER);
    if (mtlsEnabled()) {
      SSLParameters sslParameters = new SSLParameters();
      sslParameters.setProtocols(new String[]{"TLSv1.3"});
      builder
          .sslContext(RuntimeTlsContextFactory.create(properties))
          .sslParameters(sslParameters);
    }
    this.httpClient = builder.build();
  }

  @Override
  public RuntimeImageObservation prepare(
      final String advertiseUrl,
      final String image
  ) {
    String normalizedImage = image == null ? "" : image.trim();
    if (normalizedImage.isEmpty() || normalizedImage.length() > 2048) {
      throw new IllegalArgumentException("Runtime image is invalid");
    }
    return exchange(
        "POST",
        uri(advertiseUrl, "/internal/v1/images/prepare"),
        Map.of("image", normalizedImage),
        null,
        0,
        200,
        RuntimeImageObservation.class
    );
  }

  @Override
  public RuntimeMcpProbeReport probe(
      final String advertiseUrl,
      final RuntimeWorkloadDispatchRequest request
  ) {
    if (request == null) {
      throw new IllegalArgumentException("Runtime MCP probe request is required");
    }
    return exchange(
        "POST",
        uri(advertiseUrl, "/internal/v1/mcp/probe"),
        request,
        null,
        0,
        200,
        RuntimeMcpProbeReport.class
    );
  }

  @Override
  public RuntimeWorkloadObservation start(
      final String advertiseUrl,
      final RuntimeWorkloadDispatchRequest request
  ) {
    if (request == null) {
      throw new IllegalArgumentException("Runtime dispatch request is required");
    }
    return exchange(
        "POST",
        uri(advertiseUrl, "/internal/v1/workloads"),
        request,
        request.leaseId(),
        request.fencingToken(),
        201,
        RuntimeWorkloadObservation.class
    );
  }

  @Override
  public RuntimeWorkloadObservation status(
      final String advertiseUrl,
      final String workloadId,
      final String leaseId,
      final long fencingToken
  ) {
    return exchange(
        "GET",
        workloadUri(advertiseUrl, workloadId, ""),
        null,
        leaseId,
        fencingToken,
        200,
        RuntimeWorkloadObservation.class
    );
  }

  @Override
  public RuntimeWorkloadObservation stop(
      final String advertiseUrl,
      final String workloadId,
      final String leaseId,
      final long fencingToken
  ) {
    return exchange(
        "POST",
        workloadUri(advertiseUrl, workloadId, "/stop"),
        null,
        leaseId,
        fencingToken,
        200,
        RuntimeWorkloadObservation.class
    );
  }

  @Override
  public void delete(
      final String advertiseUrl,
      final String workloadId,
      final String leaseId,
      final long fencingToken
  ) {
    exchange(
        "DELETE",
        workloadUri(advertiseUrl, workloadId, ""),
        null,
        leaseId,
        fencingToken,
        204,
        Void.class
    );
  }

  private <T> T exchange(
      final String method,
      final URI uri,
      final Object body,
      final String leaseId,
      final long fencingToken,
      final int expectedStatus,
      final Class<T> responseType
  ) {
    HttpRequest.BodyPublisher publisher = body == null
        ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofByteArray(encode(body));
    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
        .timeout(dispatchTimeout())
        .header("Content-Type", "application/json")
        .method(method, publisher);
    if (leaseId != null) {
      requireIdentifier(leaseId, "Runtime lease ID");
      if (fencingToken <= 0) {
        throw new IllegalArgumentException("Runtime fencing token is invalid");
      }
      requestBuilder
          .header("X-SimplePoint-Runtime-Lease-Id", leaseId)
          .header(
              "X-SimplePoint-Runtime-Fencing-Token",
              Long.toString(fencingToken)
          );
    }
    if (!mtlsEnabled()) {
      requestBuilder.header(internalHeader(), internalToken());
    }
    HttpRequest request = requestBuilder.build();
    try {
      HttpResponse<InputStream> response = httpClient.send(
          request,
          HttpResponse.BodyHandlers.ofInputStream()
      );
      try (InputStream stream = response.body()) {
        byte[] responseBody = stream.readNBytes(MAXIMUM_RESPONSE_BYTES + 1);
        if (responseBody.length > MAXIMUM_RESPONSE_BYTES) {
          throw new RuntimeNodeOperationException(
              "Tool Runtime response exceeds the configured limit",
              response.statusCode()
          );
        }
        if (response.statusCode() != expectedStatus) {
          throw new RuntimeNodeOperationException(
              errorMessage(response.statusCode(), responseBody),
              response.statusCode()
          );
        }
        if (responseType == Void.class) {
          return null;
        }
        T value = objectMapper.readValue(responseBody, responseType);
        if (value == null) {
          throw new RuntimeNodeOperationException(
              "Tool Runtime returned an empty response",
              response.statusCode()
          );
        }
        return value;
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new RuntimeNodeOperationException(
          "Tool Runtime request was interrupted",
          0,
          ex
      );
    } catch (IOException ex) {
      throw new RuntimeNodeOperationException(
          "Tool Runtime request failed",
          0,
          ex
      );
    }
  }

  private byte[] encode(final Object value) {
    try {
      return objectMapper.writeValueAsBytes(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException(
          "Runtime dispatch request cannot be encoded",
          ex
      );
    }
  }

  private String errorMessage(final int statusCode, final byte[] responseBody) {
    String prefix = "Tool Runtime returned HTTP " + statusCode;
    try {
      String detail = objectMapper.readTree(responseBody)
          .path("message")
          .asText("");
      detail = sanitizeErrorDetail(detail);
      return detail.isEmpty() ? prefix : prefix + ": " + detail;
    } catch (RuntimeException | IOException ignored) {
      return prefix;
    }
  }

  private static String sanitizeErrorDetail(final String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    StringBuilder sanitized = new StringBuilder(
        Math.min(value.length(), MAXIMUM_ERROR_MESSAGE_CHARACTERS)
    );
    boolean previousWhitespace = false;
    for (int index = 0;
         index < value.length()
             && sanitized.length() < MAXIMUM_ERROR_MESSAGE_CHARACTERS;
         index++) {
      char character = value.charAt(index);
      if (Character.isISOControl(character)
          || Character.isWhitespace(character)) {
        if (!previousWhitespace && !sanitized.isEmpty()) {
          sanitized.append(' ');
        }
        previousWhitespace = true;
      } else {
        sanitized.append(character);
        previousWhitespace = false;
      }
    }
    return sanitized.toString().trim();
  }

  private URI workloadUri(
      final String advertiseUrl,
      final String workloadId,
      final String suffix
  ) {
    return uri(
        advertiseUrl,
        "/internal/v1/workloads/"
            + requireIdentifier(workloadId, "Runtime workload ID")
            + suffix
    );
  }

  private URI uri(final String advertiseUrl, final String path) {
    String baseUrl = normalizeBaseUrl(advertiseUrl);
    return URI.create(baseUrl + path);
  }

  private String normalizeBaseUrl(final String value) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException("Runtime advertise URL is required");
    }
    URI uri;
    try {
      uri = URI.create(value.trim());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Runtime advertise URL is invalid", ex);
    }
    if (!List.of("http", "https").contains(uri.getScheme())
        || !StringUtils.hasText(uri.getHost())
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || (StringUtils.hasText(uri.getPath()) && !"/".equals(uri.getPath()))) {
      throw new IllegalArgumentException("Runtime advertise URL is invalid");
    }
    String normalized = value.trim();
    return normalized.endsWith("/")
        ? normalized.substring(0, normalized.length() - 1) : normalized;
  }

  private String internalHeader() {
    String value = properties.getDispatchInternalHeader();
    if (!StringUtils.hasText(value)
        || value.contains("\r")
        || value.contains("\n")) {
      throw new IllegalStateException(
          "Tool Runtime internal header is not configured safely"
      );
    }
    return value.trim();
  }

  private String internalToken() {
    String value = properties.getDispatchInternalToken();
    if (!StringUtils.hasText(value)) {
      throw new IllegalStateException(
          "simplepoint.ai.runtime.dispatch-internal-token must be configured"
      );
    }
    return value.trim();
  }

  private boolean mtlsEnabled() {
    return Boolean.TRUE.equals(properties.getMtlsEnabled());
  }

  private Duration dispatchTimeout() {
    Duration value = properties.getDispatchTimeout();
    if (value == null
        || value.isZero()
        || value.isNegative()
        || value.compareTo(Duration.ofMinutes(5)) > 0) {
      throw new IllegalStateException("Runtime dispatch timeout is invalid");
    }
    return value;
  }

  private String requireIdentifier(final String value, final String field) {
    String normalized = value == null ? "" : value.trim();
    if (!IDENTIFIER.matcher(normalized).matches()) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }
}
