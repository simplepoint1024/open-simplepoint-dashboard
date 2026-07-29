package org.simplepoint.mcp.gateway.publication;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.simplepoint.mcp.gateway.config.McpGatewayProperties;
import org.simplepoint.mcp.gateway.event.McpCancellationRegistry;
import org.simplepoint.mcp.gateway.event.McpGatewayClusterEvent;
import org.simplepoint.mcp.gateway.event.McpProgressRelay;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayEventType;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayPromptGetResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayResourceReadResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPromptDescriptor;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationManifest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationPromptGetRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationResourceReadRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationToolCallRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpResourceDescriptor;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpResourceTemplateDescriptor;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpToolDescriptor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Replica-local cache of official SDK MCP servers backed by immutable publications.
 */
@Component
public class McpPublicationRegistry {

  public static final String SUBJECT_CONTEXT_KEY = "subject";

  public static final String CLIENT_CONTEXT_KEY = "clientId";

  private static final TypeRef<List<McpSchema.Content>> CONTENT_LIST_TYPE =
      new TypeRef<>() {
      };

  private static final TypeRef<List<McpSchema.ResourceContents>> RESOURCE_CONTENT_LIST_TYPE =
      new TypeRef<>() {
      };

  private static final TypeRef<List<McpSchema.PromptMessage>> PROMPT_MESSAGE_LIST_TYPE =
      new TypeRef<>() {
      };

  private final McpPublicationControlPlaneClient controlPlaneClient;

  private final McpGatewayProperties properties;

  private final McpProgressRelay progressRelay;

  private final McpCancellationRegistry cancellationRegistry;

  private final McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();

  private final ConcurrentHashMap<String, ManifestEntry> manifests =
      new ConcurrentHashMap<>();

  private final ConcurrentHashMap<String, PublishedServer> servers =
      new ConcurrentHashMap<>();

  /**
   * Creates the publication registry.
   */
  public McpPublicationRegistry(
      final McpPublicationControlPlaneClient controlPlaneClient,
      final McpGatewayProperties properties,
      final McpProgressRelay progressRelay,
      final McpCancellationRegistry cancellationRegistry
  ) {
    this.controlPlaneClient = controlPlaneClient;
    this.properties = properties;
    this.progressRelay = progressRelay;
    this.cancellationRegistry = cancellationRegistry;
  }

  /**
   * Returns a short-lived manifest cache entry.
   */
  public McpPublicationManifest manifest(final String code) {
    String normalized = normalizeCode(code);
    ManifestEntry cached = manifests.get(normalized);
    Instant now = Instant.now();
    Duration ttl = positive(
        properties.getPublicationManifestTtl(),
        Duration.ofSeconds(30)
    );
    if (cached != null && cached.loadedAt().plus(ttl).isAfter(now)) {
      return cached.manifest();
    }
    return manifests.compute(normalized, (key, current) -> {
      Instant checkedAt = Instant.now();
      if (current != null && current.loadedAt().plus(ttl).isAfter(checkedAt)) {
        return current;
      }
      McpPublicationManifest loaded = controlPlaneClient.manifest(key);
      if (loaded == null || !key.equals(loaded.code())) {
        throw new IllegalStateException("MCP publication manifest is invalid");
      }
      return new ManifestEntry(loaded, checkedAt);
    }).manifest();
  }

  /**
   * Returns a stateful Streamable HTTP transport for one publication.
   */
  public HttpServletStreamableServerTransportProvider transport(final String code) {
    McpPublicationManifest manifest = manifest(code);
    PublishedServer current = servers.get(manifest.code());
    if (current != null && current.matches(manifest)) {
      return current.transport();
    }
    return servers.compute(manifest.code(), (key, existing) -> {
      if (existing != null && existing.matches(manifest)) {
        return existing;
      }
      if (existing != null && existing.sameEndpoint(manifest)) {
        existing.update(manifest, Set.of(
            McpGatewayEventType.TOOLS_LIST_CHANGED,
            McpGatewayEventType.RESOURCES_LIST_CHANGED,
            McpGatewayEventType.PROMPTS_LIST_CHANGED
        ));
        return existing;
      }
      PublishedServer replacement = build(manifest);
      if (existing != null) {
        existing.server().closeGracefully();
      }
      return replacement;
    }).transport();
  }

  private PublishedServer build(final McpPublicationManifest manifest) {
    String endpoint = "/mcp/" + manifest.code();
    HttpServletStreamableServerTransportProvider transport =
        HttpServletStreamableServerTransportProvider.builder()
            .mcpEndpoint(endpoint)
            .contextExtractor(this::transportContext)
            .keepAliveInterval(Duration.ofSeconds(30))
            .securityValidator(headers -> validateOrigin(manifest, headers))
            .build();
    var serverBuilder = McpServer.sync(transport)
        .serverInfo(
            McpSchema.Implementation.builder(
                "open-simplepoint-" + manifest.code(),
                "1.0.0"
            ).title(manifest.name()).description(manifest.description()).build()
        )
        .instructions(manifest.description())
        .requestTimeout(positive(properties.getRequestTimeout(), Duration.ofSeconds(30)))
        .capabilities(capabilities());

    serverBuilder.tools(manifest.tools().stream()
        .map(tool -> toolSpecification(manifest, tool))
        .toList());
    serverBuilder.resources(manifest.resources().stream()
        .map(resource -> resourceSpecification(manifest, resource))
        .toList());
    serverBuilder.resourceTemplates(manifest.resourceTemplates().stream()
        .map(template -> resourceTemplateSpecification(manifest, template))
        .toList());
    serverBuilder.prompts(manifest.prompts().stream()
        .map(prompt -> promptSpecification(manifest, prompt))
        .toList());
    McpSyncServer server = serverBuilder.build();
    return new PublishedServer(manifest, transport, server);
  }

  private McpServerFeatures.SyncToolSpecification toolSpecification(
      final McpPublicationManifest manifest,
      final McpToolDescriptor tool
  ) {
    return new McpServerFeatures.SyncToolSpecification(
        jsonMapper.convertValue(tool, McpSchema.Tool.class),
        (exchange, request) -> callTool(manifest, exchange, request)
    );
  }

  private McpServerFeatures.SyncResourceSpecification resourceSpecification(
      final McpPublicationManifest manifest,
      final McpResourceDescriptor resource
  ) {
    return new McpServerFeatures.SyncResourceSpecification(
        jsonMapper.convertValue(resource, McpSchema.Resource.class),
        (exchange, request) -> readResource(manifest, exchange, request)
    );
  }

  private McpServerFeatures.SyncResourceTemplateSpecification
      resourceTemplateSpecification(
          final McpPublicationManifest manifest,
          final McpResourceTemplateDescriptor template
  ) {
    return new McpServerFeatures.SyncResourceTemplateSpecification(
        jsonMapper.convertValue(template, McpSchema.ResourceTemplate.class),
        (exchange, request) -> readResource(manifest, exchange, request)
    );
  }

  private McpServerFeatures.SyncPromptSpecification promptSpecification(
      final McpPublicationManifest manifest,
      final McpPromptDescriptor prompt
  ) {
    return new McpServerFeatures.SyncPromptSpecification(
        jsonMapper.convertValue(prompt, McpSchema.Prompt.class),
        (exchange, request) -> getPrompt(manifest, exchange, request)
    );
  }

  private McpSchema.CallToolResult callTool(
      final McpPublicationManifest manifest,
      final McpSyncServerExchange exchange,
      final McpSchema.CallToolRequest request
  ) {
    String operationId = cancellationRegistry.beginNorthbound(
        manifest.code(),
        exchange
    );
    progressRelay.register(operationId, exchange, request.meta());
    try {
      McpGatewayToolCallResult result = controlPlaneClient.callTool(
          new McpPublicationToolCallRequest(
              manifest.code(),
              request.name(),
              request.arguments(),
              context(exchange, SUBJECT_CONTEXT_KEY),
              context(exchange, CLIENT_CONTEXT_KEY),
              exchange.sessionId(),
              operationId
          )
      );
      return new McpSchema.CallToolResult(
          jsonMapper.convertValue(result.content(), CONTENT_LIST_TYPE),
          result.error(),
          result.structuredContent(),
          result.meta()
      );
    } finally {
      progressRelay.unregister(operationId);
      cancellationRegistry.completeNorthbound(
          manifest.code(),
          exchange,
          operationId
      );
    }
  }

  private McpSchema.ReadResourceResult readResource(
      final McpPublicationManifest manifest,
      final McpSyncServerExchange exchange,
      final McpSchema.ReadResourceRequest request
  ) {
    String operationId = cancellationRegistry.beginNorthbound(
        manifest.code(),
        exchange
    );
    progressRelay.register(operationId, exchange, request.meta());
    try {
      McpGatewayResourceReadResult result = controlPlaneClient.readResource(
          new McpPublicationResourceReadRequest(
              manifest.code(),
              request.uri(),
              context(exchange, SUBJECT_CONTEXT_KEY),
              context(exchange, CLIENT_CONTEXT_KEY),
              exchange.sessionId(),
              operationId
          )
      );
      return new McpSchema.ReadResourceResult(
          jsonMapper.convertValue(result.contents(), RESOURCE_CONTENT_LIST_TYPE),
          result.meta()
      );
    } finally {
      progressRelay.unregister(operationId);
      cancellationRegistry.completeNorthbound(
          manifest.code(),
          exchange,
          operationId
      );
    }
  }

  private McpSchema.GetPromptResult getPrompt(
      final McpPublicationManifest manifest,
      final McpSyncServerExchange exchange,
      final McpSchema.GetPromptRequest request
  ) {
    String operationId = cancellationRegistry.beginNorthbound(
        manifest.code(),
        exchange
    );
    progressRelay.register(operationId, exchange, request.meta());
    try {
      McpGatewayPromptGetResult result = controlPlaneClient.getPrompt(
          new McpPublicationPromptGetRequest(
              manifest.code(),
              request.name(),
              request.arguments(),
              context(exchange, SUBJECT_CONTEXT_KEY),
              context(exchange, CLIENT_CONTEXT_KEY),
              exchange.sessionId(),
              operationId
          )
      );
      return new McpSchema.GetPromptResult(
          result.description(),
          jsonMapper.convertValue(result.messages(), PROMPT_MESSAGE_LIST_TYPE),
          result.meta()
      );
    } finally {
      progressRelay.unregister(operationId);
      cancellationRegistry.completeNorthbound(
          manifest.code(),
          exchange,
          operationId
      );
    }
  }

  private McpTransportContext transportContext(final HttpServletRequest request) {
    return McpTransportContext.create(Map.of(
        SUBJECT_CONTEXT_KEY,
        attribute(request, SUBJECT_CONTEXT_KEY),
        CLIENT_CONTEXT_KEY,
        attribute(request, CLIENT_CONTEXT_KEY),
        McpCancellationRegistry.REQUEST_ID_CONTEXT_KEY,
        attribute(request, McpCancellationRegistry.REQUEST_ID_CONTEXT_KEY)
    ));
  }

  private static McpSchema.ServerCapabilities capabilities() {
    return McpSchema.ServerCapabilities.builder()
        .tools(true)
        .resources(true, true)
        .prompts(true)
        .build();
  }

  /**
   * Applies upstream changes to stateful publication servers on this replica.
   */
  @EventListener
  public void onClusterEvent(final McpGatewayClusterEvent event) {
    if (event.kind() != McpGatewayClusterEvent.Kind.PUBLICATION_CHANGE
        || event.publicationCodes() == null) {
      return;
    }
    Set<McpGatewayEventType> eventTypes = event.eventTypes() == null
        ? Set.of() : event.eventTypes();
    for (String code : event.publicationCodes()) {
      if (code == null) {
        continue;
      }
      manifests.remove(code);
      PublishedServer published = servers.get(code);
      if (published == null) {
        continue;
      }
      if (eventTypes.contains(McpGatewayEventType.RESOURCE_UPDATED)
          && event.resourceUris() != null) {
        event.resourceUris().forEach(published::notifyResourceUpdated);
      }
      if (eventTypes.contains(McpGatewayEventType.TOOLS_LIST_CHANGED)
          || eventTypes.contains(McpGatewayEventType.RESOURCES_LIST_CHANGED)
          || eventTypes.contains(McpGatewayEventType.PROMPTS_LIST_CHANGED)) {
        McpPublicationManifest updated = manifest(code);
        if (published.sameEndpoint(updated)) {
          published.update(updated, eventTypes);
        } else if (servers.remove(code, published)) {
          published.server().closeGracefully();
        }
      }
    }
  }

  private static void validateOrigin(
      final McpPublicationManifest manifest,
      final Map<String, List<String>> headers
  ) throws ServerTransportSecurityException {
    String origin = firstHeader(headers, "Origin");
    if (origin == null) {
      return;
    }
    URI resource = URI.create(manifest.canonicalResourceUri());
    String expected = resource.getScheme() + "://" + resource.getAuthority();
    if (!expected.equalsIgnoreCase(origin)) {
      throw new ServerTransportSecurityException(403, "Invalid Origin header");
    }
  }

  private static String context(
      final McpSyncServerExchange exchange,
      final String key
  ) {
    Object value = exchange.transportContext().get(key);
    return value == null ? null : value.toString();
  }

  private static String attribute(final HttpServletRequest request, final String name) {
    Object value = request.getAttribute(name);
    return value == null ? "" : value.toString();
  }

  private static String firstHeader(
      final Map<String, List<String>> headers,
      final String name
  ) {
    return headers.entrySet().stream()
        .filter(entry -> entry.getKey().equalsIgnoreCase(name))
        .flatMap(entry -> entry.getValue().stream())
        .findFirst()
        .orElse(null);
  }

  private static String normalizeCode(final String value) {
    if (value == null || !value.matches("[a-z0-9][a-z0-9_-]{0,127}")) {
      throw new IllegalArgumentException("MCP publication code is invalid");
    }
    return value;
  }

  private static Duration positive(final Duration value, final Duration fallback) {
    return value == null || value.isZero() || value.isNegative() ? fallback : value;
  }

  /**
   * Closes active SDK sessions on Gateway shutdown.
   */
  @PreDestroy
  public void close() {
    servers.values().forEach(server -> server.server().closeGracefully());
    servers.clear();
  }

  private record ManifestEntry(McpPublicationManifest manifest, Instant loadedAt) {
  }

  private final class PublishedServer {

    private volatile McpPublicationManifest manifest;

    private final HttpServletStreamableServerTransportProvider transport;

    private final McpSyncServer server;

    private PublishedServer(
        final McpPublicationManifest manifest,
        final HttpServletStreamableServerTransportProvider transport,
        final McpSyncServer server
    ) {
      this.manifest = manifest;
      this.transport = transport;
      this.server = server;
    }

    private boolean matches(final McpPublicationManifest candidate) {
      return manifest.snapshotId().equals(candidate.snapshotId())
          && sameEndpoint(candidate);
    }

    private boolean sameEndpoint(final McpPublicationManifest candidate) {
      return manifest.canonicalResourceUri().equals(
          candidate.canonicalResourceUri()
      );
    }

    private synchronized void update(
        final McpPublicationManifest updated,
        final Set<McpGatewayEventType> eventTypes
    ) {
      if (eventTypes.contains(McpGatewayEventType.TOOLS_LIST_CHANGED)) {
        server.listTools().forEach(tool -> server.removeTool(tool.name()));
        updated.tools().stream()
            .map(tool -> toolSpecification(updated, tool))
            .forEach(server::addTool);
      }
      if (eventTypes.contains(McpGatewayEventType.RESOURCES_LIST_CHANGED)) {
        server.listResources()
            .forEach(resource -> server.removeResource(resource.uri()));
        server.listResourceTemplates().forEach(template ->
            server.removeResourceTemplate(template.uriTemplate()));
        updated.resources().stream()
            .map(resource -> resourceSpecification(updated, resource))
            .forEach(server::addResource);
        updated.resourceTemplates().stream()
            .map(template -> resourceTemplateSpecification(updated, template))
            .forEach(server::addResourceTemplate);
      }
      if (eventTypes.contains(McpGatewayEventType.PROMPTS_LIST_CHANGED)) {
        server.listPrompts().forEach(prompt -> server.removePrompt(prompt.name()));
        updated.prompts().stream()
            .map(prompt -> promptSpecification(updated, prompt))
            .forEach(server::addPrompt);
      }
      manifest = updated;
      if (eventTypes.contains(McpGatewayEventType.TOOLS_LIST_CHANGED)) {
        server.notifyToolsListChanged();
      }
      if (eventTypes.contains(McpGatewayEventType.RESOURCES_LIST_CHANGED)) {
        server.notifyResourcesListChanged();
      }
      if (eventTypes.contains(McpGatewayEventType.PROMPTS_LIST_CHANGED)) {
        server.notifyPromptsListChanged();
      }
    }

    private void notifyResourceUpdated(final String uri) {
      if (uri != null && !uri.isBlank()) {
        server.notifyResourcesUpdated(
            new McpSchema.ResourcesUpdatedNotification(uri)
        );
      }
    }

    private HttpServletStreamableServerTransportProvider transport() {
      return transport;
    }

    private McpSyncServer server() {
      return server;
    }
  }
}
