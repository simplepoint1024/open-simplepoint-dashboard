package org.simplepoint.plugin.ai.catalog.service.registry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.simplepoint.plugin.ai.catalog.api.model.McpServerDescriptor;
import org.springframework.stereotype.Component;

/**
 * Size-bounded, redirect-free and SSRF-resistant official Registry client.
 */
@Component
class HttpOfficialMcpRegistryClient implements OfficialMcpRegistryClient {

  private final AiCatalogProperties properties;

  private final ObjectMapper objectMapper;

  private final HttpClient httpClient;

  HttpOfficialMcpRegistryClient(
      final AiCatalogProperties properties,
      final ObjectMapper objectMapper
  ) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(properties.getConnectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  @Override
  public OfficialRegistryPage fetch(
      final String cursor,
      final Instant updatedSince,
      final boolean includeDeleted,
      final int limit
  ) {
    URI requestUri = buildUri(cursor, updatedSince, includeDeleted, limit);
    validateEndpoint(requestUri);
    HttpRequest request = HttpRequest.newBuilder(requestUri)
        .GET()
        .timeout(properties.getRequestTimeout())
        .header("Accept", "application/json")
        .header("User-Agent", "open-simplepoint-ai-catalog/1")
        .build();
    try {
      HttpResponse<InputStream> response = httpClient.send(
          request,
          HttpResponse.BodyHandlers.ofInputStream()
      );
      if (response.statusCode() != 200) {
        response.body().close();
        throw new IllegalStateException(
            "Official MCP Registry returned HTTP " + response.statusCode()
        );
      }
      try (InputStream body = response.body()) {
        int maximumBytes = positive(
            properties.getMaximumResponseBytes(),
            2 * 1024 * 1024
        );
        byte[] content = body.readNBytes(maximumBytes + 1);
        if (content.length > maximumBytes) {
          throw new IllegalStateException(
              "Official MCP Registry response exceeds configured limit"
          );
        }
        return parsePage(new String(content, StandardCharsets.UTF_8));
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Official MCP Registry request interrupted", ex);
    } catch (IOException ex) {
      throw new IllegalStateException("Official MCP Registry request failed", ex);
    }
  }

  OfficialRegistryPage parsePage(final String json) {
    try {
      JsonNode root = objectMapper.readTree(json);
      JsonNode servers = root.path("servers");
      if (!servers.isArray()) {
        throw new IllegalArgumentException(
            "Official MCP Registry response has no servers array"
        );
      }
      List<OfficialRegistryEntry> entries = new ArrayList<>();
      for (JsonNode wrapper : servers) {
        JsonNode server = wrapper.path("server");
        String name = text(server, "name");
        String version = text(server, "version");
        if (name == null || version == null) {
          continue;
        }
        JsonNode official = wrapper.path("_meta")
            .path("io.modelcontextprotocol.registry/official");
        JsonNode remote = streamableHttpRemote(server.path("remotes"));
        String status = text(official, "status");
        String repositoryUrl = server.path("repository").isObject()
            ? text(server.path("repository"), "url") : null;
        McpServerDescriptor descriptor = descriptor(server);
        entries.add(new OfficialRegistryEntry(
            trim(name, 512),
            trim(version, 128),
            trim(first(text(server, "title"), name), 256),
            trim(text(server, "description"), 2048),
            first(status, "active").toLowerCase(Locale.ROOT),
            remote == null ? null : text(remote, "type"),
            remote == null ? null : trim(text(remote, "url"), 2048),
            trim(repositoryUrl, 2048),
            trim(text(server, "websiteUrl"), 2048),
            instant(text(official, "publishedAt")),
            instant(text(official, "updatedAt")),
            descriptor,
            trim(objectMapper.writeValueAsString(wrapper), 1024 * 1024)
        ));
      }
      return new OfficialRegistryPage(
          List.copyOf(entries),
          trim(text(root.path("metadata"), "nextCursor"), 2048)
      );
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException(
          "Official MCP Registry returned invalid JSON",
          ex
      );
    }
  }

  private McpServerDescriptor descriptor(final JsonNode server)
      throws JsonProcessingException {
    JsonNode repositoryNode = server.path("repository");
    McpServerDescriptor.Repository repository = repositoryNode.isObject()
        && text(repositoryNode, "url") != null
        && text(repositoryNode, "source") != null
        ? new McpServerDescriptor.Repository(
            text(repositoryNode, "url"),
            text(repositoryNode, "source"),
            text(repositoryNode, "id"),
            text(repositoryNode, "subfolder")
        ) : null;
    return new McpServerDescriptor(
        text(server, "$schema"),
        text(server, "name"),
        text(server, "title"),
        text(server, "description"),
        text(server, "version"),
        repository,
        text(server, "websiteUrl"),
        packages(server.path("packages")),
        transports(server.path("remotes")),
        objectMapper.writeValueAsString(server)
    );
  }

  private static List<McpServerDescriptor.Package> packages(
      final JsonNode packages
  ) {
    if (!packages.isArray()) {
      return List.of();
    }
    List<McpServerDescriptor.Package> result = new ArrayList<>();
    for (JsonNode value : packages) {
      JsonNode transport = value.path("transport");
      if (text(value, "registryType") == null
          || text(value, "identifier") == null
          || text(transport, "type") == null) {
        continue;
      }
      result.add(new McpServerDescriptor.Package(
          text(value, "registryType"),
          text(value, "identifier"),
          text(value, "version"),
          text(value, "registryBaseUrl"),
          text(value, "fileSha256"),
          text(value, "runtimeHint"),
          transport(transport),
          arguments(value.path("runtimeArguments")),
          arguments(value.path("packageArguments")),
          inputs(value.path("environmentVariables"))
      ));
    }
    return List.copyOf(result);
  }

  private static List<McpServerDescriptor.Transport> transports(
      final JsonNode transports
  ) {
    if (!transports.isArray()) {
      return List.of();
    }
    List<McpServerDescriptor.Transport> result = new ArrayList<>();
    for (JsonNode value : transports) {
      if (text(value, "type") != null) {
        result.add(transport(value));
      }
    }
    return List.copyOf(result);
  }

  private static McpServerDescriptor.Transport transport(final JsonNode value) {
    return new McpServerDescriptor.Transport(
        text(value, "type"),
        text(value, "url"),
        inputs(value.path("headers")),
        inputMap(value.path("variables"))
    );
  }

  private static List<McpServerDescriptor.Argument> arguments(
      final JsonNode arguments
  ) {
    if (!arguments.isArray()) {
      return List.of();
    }
    List<McpServerDescriptor.Argument> result = new ArrayList<>();
    for (JsonNode value : arguments) {
      String type = text(value, "type");
      if (!"named".equals(type) && !"positional".equals(type)) {
        continue;
      }
      result.add(new McpServerDescriptor.Argument(
          type,
          text(value, "name"),
          text(value, "valueHint"),
          value.path("isRepeated").asBoolean(false),
          input(value),
          inputMap(value.path("variables"))
      ));
    }
    return List.copyOf(result);
  }

  private static List<McpServerDescriptor.Input> inputs(final JsonNode values) {
    if (!values.isArray()) {
      return List.of();
    }
    List<McpServerDescriptor.Input> result = new ArrayList<>();
    for (JsonNode value : values) {
      result.add(input(value));
    }
    return List.copyOf(result);
  }

  private static Map<String, McpServerDescriptor.Input> inputMap(
      final JsonNode values
  ) {
    if (!values.isObject()) {
      return Map.of();
    }
    Map<String, McpServerDescriptor.Input> result = new LinkedHashMap<>();
    values.fields().forEachRemaining(
        entry -> result.put(entry.getKey(), input(entry.getValue()))
    );
    return Map.copyOf(result);
  }

  private static McpServerDescriptor.Input input(final JsonNode value) {
    List<String> choices = new ArrayList<>();
    if (value.path("choices").isArray()) {
      value.path("choices").forEach(choice -> {
        if (choice.isValueNode()) {
          choices.add(choice.asText());
        }
      });
    }
    return new McpServerDescriptor.Input(
        text(value, "name"),
        text(value, "description"),
        text(value, "format"),
        value.path("isRequired").asBoolean(false),
        value.path("isSecret").asBoolean(false),
        text(value, "default"),
        text(value, "placeholder"),
        text(value, "value"),
        choices
    );
  }

  private URI buildUri(
      final String cursor,
      final Instant updatedSince,
      final boolean includeDeleted,
      final int limit
  ) {
    StringBuilder query = new StringBuilder("limit=")
        .append(Math.max(1, Math.min(limit, 100)))
        .append("&version=latest");
    append(query, "cursor", cursor);
    if (updatedSince != null) {
      append(query, "updated_since", updatedSince.toString());
      query.append("&include_deleted=").append(includeDeleted);
    }
    String base = properties.getOfficialRegistryUrl();
    if (base == null || base.isBlank()) {
      throw new IllegalStateException("Official MCP Registry URL is not configured");
    }
    return URI.create(base.replaceAll("/+$", "") + "/v0.1/servers?" + query);
  }

  private void validateEndpoint(final URI uri) {
    boolean allowPrivate = Boolean.TRUE.equals(properties.getAllowPrivateRegistry());
    if (!"https".equalsIgnoreCase(uri.getScheme())
        && !(allowPrivate && "http".equalsIgnoreCase(uri.getScheme()))) {
      throw new IllegalStateException("Official MCP Registry must use HTTPS");
    }
    if (uri.getRawUserInfo() != null || uri.getHost() == null) {
      throw new IllegalStateException("Official MCP Registry URL is invalid");
    }
    if (allowPrivate) {
      return;
    }
    try {
      for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
        if (address.isAnyLocalAddress()
            || address.isLoopbackAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
          throw new IllegalStateException(
              "Official MCP Registry resolves to a private network"
          );
        }
      }
    } catch (IOException ex) {
      throw new IllegalStateException(
          "Official MCP Registry host cannot be resolved",
          ex
      );
    }
  }

  private static JsonNode streamableHttpRemote(final JsonNode remotes) {
    if (!remotes.isArray()) {
      return null;
    }
    for (JsonNode remote : remotes) {
      if ("streamable-http".equalsIgnoreCase(text(remote, "type"))
          && text(remote, "url") != null) {
        return remote;
      }
    }
    return null;
  }

  private static String text(final JsonNode node, final String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() || !value.isValueNode()
        ? null : value.asText();
  }

  private static String first(final String value, final String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static Instant instant(final String value) {
    try {
      return value == null || value.isBlank() ? null : Instant.parse(value);
    } catch (DateTimeParseException ignored) {
      return null;
    }
  }

  private static String trim(final String value, final int maximumLength) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    if (normalized.isEmpty()) {
      return null;
    }
    return normalized.length() <= maximumLength
        ? normalized : normalized.substring(0, maximumLength);
  }

  private static void append(
      final StringBuilder query,
      final String name,
      final String value
  ) {
    if (value != null && !value.isBlank()) {
      query.append('&')
          .append(name)
          .append('=')
          .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }
  }

  private static int positive(final Integer value, final int fallback) {
    return value == null || value <= 0 ? fallback : value;
  }
}
