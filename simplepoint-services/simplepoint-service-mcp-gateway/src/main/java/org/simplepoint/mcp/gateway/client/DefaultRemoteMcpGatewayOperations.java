package org.simplepoint.mcp.gateway.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.simplepoint.mcp.gateway.config.McpGatewayProperties;
import org.simplepoint.mcp.gateway.event.McpCancellationRegistry;
import org.simplepoint.mcp.gateway.event.McpUpstreamEventCoordinator;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayCancellationRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayConnection;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayConnectionKind;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayDiscoveryResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayEventType;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthDiscoveryRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthDiscoveryResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthRegistrationRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthRegistrationResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthTokenRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthTokenResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOperations;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayPromptGetRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayPromptGetResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayResourceReadRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayResourceReadResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayStatus;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPromptDescriptor;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpResourceDescriptor;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpResourceTemplateDescriptor;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpToolDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Official Java SDK based client for remote Streamable HTTP MCP servers.
 */
@Service
public class DefaultRemoteMcpGatewayOperations implements McpGatewayOperations {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(DefaultRemoteMcpGatewayOperations.class);

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
  };

  private static final TypeReference<List<Map<String, Object>>> MAP_LIST_TYPE =
      new TypeReference<>() {
      };

  private final McpEndpointPolicy endpointPolicy;

  private final McpGatewayProperties properties;

  private final ObjectMapper objectMapper;

  private final RemoteMcpOauthClient oauthClient;

  private final McpUpstreamEventCoordinator eventCoordinator;

  private final McpCancellationRegistry cancellationRegistry;

  private final RuntimeMcpTlsClient runtimeTlsClient;

  private final ConcurrentHashMap<String, RemoteSession> sessions =
      new ConcurrentHashMap<>();

  private final ConcurrentHashMap<String, PendingChanges> pendingChanges =
      new ConcurrentHashMap<>();

  private final AtomicInteger eventThreadSequence = new AtomicInteger();

  private final ExecutorService eventExecutor = Executors.newFixedThreadPool(
      2,
      runnable -> {
        Thread thread = new Thread(
            runnable,
            "mcp-upstream-event-" + eventThreadSequence.incrementAndGet()
        );
        thread.setDaemon(true);
        return thread;
      }
  );

  /**
   * Creates the remote MCP operations service.
   *
   * @param endpointPolicy endpoint security policy
   * @param properties gateway limits
   * @param objectMapper platform JSON mapper
   */
  public DefaultRemoteMcpGatewayOperations(
      final McpEndpointPolicy endpointPolicy,
      final McpGatewayProperties properties,
      final ObjectMapper objectMapper,
      final RemoteMcpOauthClient oauthClient,
      final McpUpstreamEventCoordinator eventCoordinator,
      final McpCancellationRegistry cancellationRegistry
  ) {
    this.endpointPolicy = endpointPolicy;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.oauthClient = oauthClient;
    this.eventCoordinator = eventCoordinator;
    this.cancellationRegistry = cancellationRegistry;
    this.runtimeTlsClient = new RuntimeMcpTlsClient(properties);
  }

  @Override
  public McpGatewayStatus status() {
    String instanceId = System.getenv().getOrDefault("HOSTNAME", "local");
    return new McpGatewayStatus(
        "mcp-gateway",
        "UP",
        "2025-11-25",
        "2.0.0",
        instanceId,
        Instant.now()
    );
  }

  @Override
  public void cancel(final McpGatewayCancellationRequest request) {
    if (request == null) {
      throw new IllegalArgumentException(
          "MCP cancellation request must not be null"
      );
    }
    cancellationRegistry.cancelOperation(
        request.operationId(),
        request.reason()
    );
  }

  @Override
  public McpGatewayDiscoveryResult discover(final McpGatewayConnection connection) {
    return withClient(connection, session -> {
      McpGatewayDiscoveryResult discovery = discovery(session);
      discovery.resources().forEach(resource -> session.subscribe(resource.uri()));
      return discovery;
    });
  }

  @Override
  public McpGatewayResourceReadResult readResource(
      final McpGatewayResourceReadRequest request
  ) {
    if (request == null || request.connection() == null) {
      throw new IllegalArgumentException("MCP resource connection must not be null");
    }
    String uri = normalizeResourceUri(request.uri());
    return withClient(request.connection(), request.operationId(), session -> {
      session.initialize();
      session.subscribe(uri);
      McpSchema.ReadResourceResult result = session.client().readResource(
          McpSchema.ReadResourceRequest.builder(uri)
              .meta(request.meta() == null ? Map.of() : request.meta())
              .build()
      );
      McpGatewayResourceReadResult mapped = new McpGatewayResourceReadResult(
          objectMapper.convertValue(result.contents(), MAP_LIST_TYPE),
          result.meta() == null ? Map.of() : result.meta()
      );
      assertSerializedSize(mapped, properties.getMaxResultBytes(), "MCP resource result");
      return mapped;
    });
  }

  @Override
  public McpGatewayPromptGetResult getPrompt(
      final McpGatewayPromptGetRequest request
  ) {
    if (request == null || request.connection() == null) {
      throw new IllegalArgumentException("MCP prompt connection must not be null");
    }
    String name = normalizeCapabilityName(request.name(), "prompt");
    Map<String, Object> arguments = request.arguments() == null
        ? Map.of() : request.arguments();
    assertSerializedSize(arguments, properties.getMaxArgumentsBytes(), "MCP prompt arguments");
    return withClient(request.connection(), request.operationId(), session -> {
      session.initialize();
      McpSchema.GetPromptResult result = session.client().getPrompt(
          McpSchema.GetPromptRequest.builder(name)
              .arguments(arguments)
              .meta(request.meta() == null ? Map.of() : request.meta())
              .build()
      );
      McpGatewayPromptGetResult mapped = new McpGatewayPromptGetResult(
          result.description(),
          mapPromptMessages(result.messages()),
          result.meta() == null ? Map.of() : result.meta()
      );
      assertSerializedSize(mapped, properties.getMaxResultBytes(), "MCP prompt result");
      return mapped;
    });
  }

  @Override
  public McpGatewayToolCallResult callTool(final McpGatewayToolCallRequest request) {
    if (request == null || request.connection() == null) {
      throw new IllegalArgumentException("MCP tool call connection must not be null");
    }
    String toolName = normalizeToolName(request.toolName());
    Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
    assertSerializedSize(arguments, properties.getMaxArgumentsBytes(), "MCP tool arguments");
    return withClient(request.connection(), request.operationId(), session -> {
      session.initialize();
      McpSchema.Tool tool = session.client().listTools().tools().stream()
          .filter(candidate -> candidate.name().equals(toolName))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException("MCP tool does not exist: " + toolName));
      var validation = McpJsonDefaults.getSchemaValidator().validate(
          tool.inputSchema(),
          arguments
      );
      if (!validation.valid()) {
        throw new IllegalArgumentException(
            "MCP tool arguments do not match inputSchema: " + validation.errorMessage()
        );
      }
      McpSchema.CallToolResult result = session.client().callTool(
          McpSchema.CallToolRequest.builder(toolName)
              .arguments(arguments)
              .meta(request.meta() == null ? Map.of() : request.meta())
              .build()
      );
      McpGatewayToolCallResult mapped = new McpGatewayToolCallResult(
          mapContent(result.content()),
          Boolean.TRUE.equals(result.isError()),
          result.structuredContent(),
          result.meta()
      );
      assertSerializedSize(mapped, properties.getMaxResultBytes(), "MCP tool result");
      return mapped;
    });
  }

  private List<Map<String, Object>> mapPromptMessages(
      final List<McpSchema.PromptMessage> messages
  ) {
    if (messages == null || messages.isEmpty()) {
      return List.of();
    }
    return messages.stream().map(message -> {
      Map<String, Object> mapped = new LinkedHashMap<>();
      mapped.put(
          "role",
          message.role().name().toLowerCase(Locale.ROOT)
      );
      mapped.put("content", mapContent(message.content()));
      return mapped;
    }).toList();
  }

  private List<Map<String, Object>> mapContent(
      final List<McpSchema.Content> content
  ) {
    if (content == null || content.isEmpty()) {
      return List.of();
    }
    return content.stream().map(this::mapContent).toList();
  }

  private Map<String, Object> mapContent(
      final McpSchema.Content content
  ) {
    Map<String, Object> mapped = new LinkedHashMap<>(
        objectMapper.convertValue(content, MAP_TYPE)
    );
    mapped.put("type", content.type());
    return mapped;
  }

  @Override
  public McpGatewayOauthDiscoveryResult discoverOauth(
      final McpGatewayOauthDiscoveryRequest request
  ) {
    return oauthClient.discover(request);
  }

  @Override
  public McpGatewayOauthRegistrationResult registerOauthClient(
      final McpGatewayOauthRegistrationRequest request
  ) {
    return oauthClient.register(request);
  }

  @Override
  public McpGatewayOauthTokenResult exchangeOauthToken(
      final McpGatewayOauthTokenRequest request
  ) {
    return oauthClient.exchange(request);
  }

  private <T> T withClient(
      final McpGatewayConnection connection,
      final Function<RemoteSession, T> operation
  ) {
    return withClient(connection, null, operation);
  }

  private <T> T withClient(
      final McpGatewayConnection connection,
      final String operationId,
      final Function<RemoteSession, T> operation
  ) {
    if (connection == null) {
      throw new IllegalArgumentException("MCP connection must not be null");
    }
    String connectionId = normalizeConnectionId(connection.connectionId());
    McpGatewayConnectionKind kind = connection.kind() == null
        ? McpGatewayConnectionKind.REMOTE_HTTP : connection.kind();
    McpEndpointPolicy.Endpoint endpoint = endpointPolicy.validate(
        connection.endpointUrl(),
        connection.allowPrivateNetwork()
    );
    validateManagedConnection(connection, kind, endpoint);
    String authorizationHeader = validateAuthorizationHeader(connection.authorizationHeader());
    String sessionKey = sessionKey(
        connectionId,
        endpoint,
        authorizationHeader,
        connection,
        kind
    );
    evictExpiredSessions();
    RemoteSession session = sessions.computeIfAbsent(
        sessionKey,
        ignored -> createSession(
            sessionKey,
            connectionId,
            endpoint,
            authorizationHeader,
            connection,
            kind
        )
    );
    session.touch();
    trimSessions();
    cancellationRegistry.beginSouthbound(operationId);
    try {
      return operation.apply(session);
    } catch (IllegalArgumentException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      invalidate(sessionKey, session);
      throw ex;
    } finally {
      cancellationRegistry.completeSouthbound(operationId);
    }
  }

  private RemoteSession createSession(
      final String sessionKey,
      final String connectionId,
      final McpEndpointPolicy.Endpoint endpoint,
      final String authorizationHeader,
      final McpGatewayConnection connection,
      final McpGatewayConnectionKind kind
  ) {
    HttpClientStreamableHttpTransport.Builder transportBuilder =
        HttpClientStreamableHttpTransport.builder(endpoint.baseUri())
            .endpoint(endpoint.endpointPath())
            .connectTimeout(positive(properties.getConnectTimeout(), Duration.ofSeconds(10)))
            .customizeClient(builder -> {
              builder.followRedirects(HttpClient.Redirect.NEVER);
              if (kind == McpGatewayConnectionKind.MANAGED_RUNTIME) {
                runtimeTlsClient.customize(builder);
              }
            });
    if (authorizationHeader != null
        || kind == McpGatewayConnectionKind.MANAGED_RUNTIME) {
      transportBuilder.httpRequestCustomizer(
          (builder, method, uri, body, context) -> {
            if (authorizationHeader != null) {
              builder.header("Authorization", authorizationHeader);
            }
            if (kind == McpGatewayConnectionKind.MANAGED_RUNTIME) {
              builder.header(
                  "X-SimplePoint-Runtime-Lease-Id",
                  connection.runtimeLeaseId()
              );
              builder.header(
                  "X-SimplePoint-Runtime-Fencing-Token",
                  Long.toString(connection.runtimeFencingToken())
              );
            }
          }
      );
    }
    HttpClientStreamableHttpTransport transport = transportBuilder.build();
    AtomicReference<RemoteSession> sessionReference = new AtomicReference<>();
    McpClient.SyncSpec clientSpec = McpClient.sync(transport)
        .clientInfo(McpSchema.Implementation.builder(
            "open-simplepoint-mcp-gateway",
            "1.0.0"
        ).title("Open SimplePoint MCP Gateway").build())
        .initializationTimeout(positive(
            properties.getInitializationTimeout(),
            Duration.ofSeconds(15)
        ))
        .requestTimeout(positive(properties.getRequestTimeout(), Duration.ofSeconds(30)))
        // Upstream output schemas are advisory and frequently lag live APIs.
        // Inputs remain validated locally; results stay size-bounded and are
        // validated by the owning Skill/Workflow contract where applicable.
        .enableCallToolSchemaCaching(false);
    clientSpec.toolsChangeConsumer(ignored ->
        signalChange(sessionReference.get(), McpGatewayEventType.TOOLS_LIST_CHANGED));
    clientSpec.resourcesChangeConsumer(ignored ->
        signalChange(sessionReference.get(), McpGatewayEventType.RESOURCES_LIST_CHANGED));
    clientSpec.promptsChangeConsumer(ignored ->
        signalChange(sessionReference.get(), McpGatewayEventType.PROMPTS_LIST_CHANGED));
    clientSpec.resourcesUpdateConsumer(contents ->
        signalResourceUpdate(sessionReference.get(), contents));
    clientSpec.progressConsumer(eventCoordinator::publishProgress);
    clientSpec.loggingConsumer(notification -> LOGGER.debug(
        "Remote MCP log [{}]: {}",
        notification.level(),
        notification.data()
    ));
    McpSyncClient client = clientSpec.build();
    RemoteSession session = new RemoteSession(
        sessionKey,
        connectionId,
        client,
        Math.max(1, properties.getMaxSubscriptionsPerSession())
    );
    sessionReference.set(session);
    return session;
  }

  private McpGatewayDiscoveryResult discovery(final RemoteSession session) {
    McpSchema.InitializeResult initialized = session.initialize();
    McpSchema.Implementation server = initialized.serverInfo();
    McpSchema.ServerCapabilities capabilities = initialized.capabilities();
    List<McpToolDescriptor> tools = capabilities.tools() == null
        ? List.of()
        : session.client().listTools().tools().stream()
            .map(this::toDescriptor)
            .toList();
    List<McpResourceDescriptor> resources = capabilities.resources() == null
        ? List.of()
        : session.client().listResources().resources().stream()
            .map(this::toDescriptor)
            .toList();
    List<McpResourceTemplateDescriptor> resourceTemplates =
        capabilities.resources() == null
            ? List.of()
            : session.client().listResourceTemplates().resourceTemplates().stream()
                .map(this::toDescriptor)
                .toList();
    List<McpPromptDescriptor> prompts = capabilities.prompts() == null
        ? List.of()
        : session.client().listPrompts().prompts().stream()
            .map(this::toDescriptor)
            .toList();
    return new McpGatewayDiscoveryResult(
        initialized.protocolVersion(),
        server.name(),
        server.title(),
        server.version(),
        server.description(),
        initialized.instructions(),
        toMap(capabilities),
        tools,
        resources,
        resourceTemplates,
        prompts
    );
  }

  private void signalChange(
      final RemoteSession session,
      final McpGatewayEventType eventType
  ) {
    if (session == null || session.closed()) {
      return;
    }
    PendingChanges pending = pendingChanges.computeIfAbsent(
        session.sessionKey(),
        ignored -> new PendingChanges()
    );
    if (pending.add(eventType)) {
      eventExecutor.execute(() -> publishPendingChanges(session, pending));
    }
  }

  private void publishPendingChanges(
      final RemoteSession session,
      final PendingChanges pending
  ) {
    while (!session.closed()) {
      Set<McpGatewayEventType> eventTypes = pending.drain();
      if (eventTypes.isEmpty()) {
        pendingChanges.remove(session.sessionKey(), pending);
        return;
      }
      try {
        eventCoordinator.publishChange(
            session.connectionId(),
            eventTypes,
            List.of(),
            discovery(session)
        );
      } catch (RuntimeException ex) {
        LOGGER.warn(
            "Unable to process remote MCP list change for connection {}: {}",
            session.connectionId(),
            ex.getMessage()
        );
      }
    }
  }

  private void signalResourceUpdate(
      final RemoteSession session,
      final List<McpSchema.ResourceContents> contents
  ) {
    if (session == null || session.closed()) {
      return;
    }
    List<String> uris = contents == null ? List.of() : contents.stream()
        .map(McpSchema.ResourceContents::uri)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .limit(1000)
        .toList();
    if (uris.isEmpty()) {
      return;
    }
    eventExecutor.execute(() -> {
      try {
        eventCoordinator.publishChange(
            session.connectionId(),
            Set.of(McpGatewayEventType.RESOURCE_UPDATED),
            uris,
            null
        );
      } catch (RuntimeException ex) {
        LOGGER.warn(
            "Unable to process remote MCP resource update for connection {}: {}",
            session.connectionId(),
            ex.getMessage()
        );
      }
    });
  }

  private void evictExpiredSessions() {
    Duration configured = properties.getRemoteSessionIdleTimeout();
    Duration timeout = positive(configured, Duration.ofMinutes(5));
    Instant cutoff = Instant.now().minus(timeout);
    sessions.forEach((key, session) -> {
      if (session.lastAccessedAt().isBefore(cutoff)) {
        invalidate(key, session);
      }
    });
  }

  private void trimSessions() {
    int limit = Math.max(1, properties.getMaxRemoteSessions());
    int excess = sessions.size() - limit;
    if (excess <= 0) {
      return;
    }
    List<RemoteSession> oldest = new ArrayList<>(sessions.values());
    oldest.sort(Comparator.comparing(RemoteSession::lastAccessedAt));
    oldest.stream().limit(excess)
        .forEach(session -> invalidate(session.sessionKey(), session));
  }

  private void invalidate(final String key, final RemoteSession session) {
    if (sessions.remove(key, session)) {
      pendingChanges.remove(key);
      session.close();
    }
  }

  private static String sessionKey(
      final String connectionId,
      final McpEndpointPolicy.Endpoint endpoint,
      final String authorizationHeader,
      final McpGatewayConnection connection,
      final McpGatewayConnectionKind kind
  ) {
    String material = connectionId + '\u0000' + endpoint.baseUri() + '\u0000'
        + endpoint.endpointPath() + '\u0000'
        + (authorizationHeader == null ? "" : authorizationHeader) + '\u0000'
        + connection.allowPrivateNetwork() + '\u0000'
        + kind + '\u0000'
        + (connection.runtimeLeaseId() == null
            ? "" : connection.runtimeLeaseId()) + '\u0000'
        + connection.runtimeFencingToken();
    try {
      return java.util.HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256")
              .digest(material.getBytes(StandardCharsets.UTF_8))
      );
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }

  private static void validateManagedConnection(
      final McpGatewayConnection connection,
      final McpGatewayConnectionKind kind,
      final McpEndpointPolicy.Endpoint endpoint
  ) {
    if (kind != McpGatewayConnectionKind.MANAGED_RUNTIME) {
      return;
    }
    if (!endpoint.baseUri().startsWith("https://")
        || !connection.allowPrivateNetwork()
        || connection.authorizationHeader() != null
        || connection.runtimeLeaseId() == null
        || connection.runtimeLeaseId().isBlank()
        || connection.runtimeFencingToken() <= 0) {
      throw new IllegalArgumentException(
          "Managed Runtime MCP connection envelope is invalid"
      );
    }
  }

  private McpToolDescriptor toDescriptor(final McpSchema.Tool tool) {
    return new McpToolDescriptor(
        tool.name(),
        tool.title(),
        tool.description(),
        tool.inputSchema(),
        tool.outputSchema(),
        tool.annotations() == null ? Map.of() : toMap(tool.annotations()),
        tool.icons() == null ? List.of() : objectMapper.convertValue(tool.icons(), MAP_LIST_TYPE)
    );
  }

  private McpResourceDescriptor toDescriptor(final McpSchema.Resource resource) {
    return new McpResourceDescriptor(
        resource.uri(),
        resource.name(),
        resource.title(),
        resource.description(),
        resource.mimeType(),
        resource.size(),
        resource.annotations() == null ? Map.of() : toMap(resource.annotations()),
        resource.meta() == null ? Map.of() : resource.meta(),
        resource.icons() == null
            ? List.of() : objectMapper.convertValue(resource.icons(), MAP_LIST_TYPE)
    );
  }

  private McpResourceTemplateDescriptor toDescriptor(
      final McpSchema.ResourceTemplate template
  ) {
    return new McpResourceTemplateDescriptor(
        template.uriTemplate(),
        template.name(),
        template.title(),
        template.description(),
        template.mimeType(),
        template.annotations() == null ? Map.of() : toMap(template.annotations()),
        template.meta() == null ? Map.of() : template.meta(),
        template.icons() == null
            ? List.of() : objectMapper.convertValue(template.icons(), MAP_LIST_TYPE)
    );
  }

  private McpPromptDescriptor toDescriptor(final McpSchema.Prompt prompt) {
    return new McpPromptDescriptor(
        prompt.name(),
        prompt.title(),
        prompt.description(),
        prompt.arguments() == null
            ? List.of() : objectMapper.convertValue(prompt.arguments(), MAP_LIST_TYPE),
        prompt.meta() == null ? Map.of() : prompt.meta(),
        prompt.icons() == null
            ? List.of() : objectMapper.convertValue(prompt.icons(), MAP_LIST_TYPE)
    );
  }

  private Map<String, Object> toMap(final Object value) {
    return value == null ? Map.of() : objectMapper.convertValue(value, MAP_TYPE);
  }

  private void assertSerializedSize(
      final Object value,
      final long configuredLimit,
      final String label
  ) {
    long limit = configuredLimit > 0 ? configuredLimit : 1;
    try {
      int size = objectMapper.writeValueAsBytes(value).length;
      if (size > limit) {
        throw new IllegalArgumentException(label + " exceeds " + limit + " bytes");
      }
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      throw new IllegalArgumentException(label + " is not valid JSON", ex);
    }
  }

  private static String validateAuthorizationHeader(final String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim();
    if (normalized.contains("\r") || normalized.contains("\n")) {
      throw new IllegalArgumentException("MCP authorization header contains invalid characters");
    }
    if (!normalized.regionMatches(true, 0, "Bearer ", 0, 7)
        || normalized.length() == 7) {
      throw new IllegalArgumentException(
          "The first MCP Gateway slice supports only Bearer authorization"
      );
    }
    return normalized;
  }

  private static String normalizeConnectionId(final String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("MCP connection ID must not be blank");
    }
    String normalized = value.trim();
    if (normalized.length() > 128 || normalized.contains("\r")
        || normalized.contains("\n")) {
      throw new IllegalArgumentException("MCP connection ID is invalid");
    }
    return normalized;
  }

  private static String normalizeToolName(final String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("MCP tool name must not be blank");
    }
    String normalized = value.trim();
    if (normalized.length() > 128
        || !normalized.matches("[A-Za-z0-9_.-]+")) {
      throw new IllegalArgumentException("MCP tool name is invalid");
    }
    return normalized;
  }

  private static String normalizeCapabilityName(
      final String value,
      final String capability
  ) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("MCP " + capability + " name must not be blank");
    }
    String normalized = value.trim();
    if (normalized.length() > 128 || normalized.contains("\r")
        || normalized.contains("\n")) {
      throw new IllegalArgumentException("MCP " + capability + " name is invalid");
    }
    return normalized;
  }

  private static String normalizeResourceUri(final String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("MCP resource URI must not be blank");
    }
    String normalized = value.trim();
    if (normalized.length() > 4096 || normalized.contains("\r")
        || normalized.contains("\n")) {
      throw new IllegalArgumentException("MCP resource URI is invalid");
    }
    try {
      URI.create(normalized);
      return normalized;
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("MCP resource URI is invalid", ex);
    }
  }

  private static Duration positive(final Duration value, final Duration fallback) {
    return value == null || value.isZero() || value.isNegative() ? fallback : value;
  }

  /**
   * Closes pooled southbound sessions and notification workers.
   */
  @PreDestroy
  public void close() {
    eventExecutor.shutdownNow();
    sessions.values().forEach(RemoteSession::close);
    sessions.clear();
    pendingChanges.clear();
  }

  private final class RemoteSession {

    private final String sessionKey;

    private final String connectionId;

    private final McpSyncClient client;

    private final int maxSubscriptions;

    private final Set<String> subscriptions = ConcurrentHashMap.newKeySet();

    private volatile McpSchema.InitializeResult initialized;

    private volatile Instant lastAccessedAt = Instant.now();

    private volatile boolean closed;

    private RemoteSession(
        final String sessionKey,
        final String connectionId,
        final McpSyncClient client,
        final int maxSubscriptions
    ) {
      this.sessionKey = sessionKey;
      this.connectionId = connectionId;
      this.client = client;
      this.maxSubscriptions = maxSubscriptions;
    }

    private McpSchema.InitializeResult initialize() {
      McpSchema.InitializeResult current = initialized;
      if (current != null) {
        return current;
      }
      synchronized (this) {
        if (initialized == null) {
          initialized = client.initialize();
        }
        return initialized;
      }
    }

    private void subscribe(final String uri) {
      McpSchema.ServerCapabilities.ResourceCapabilities resources =
          initialize().capabilities().resources();
      if (resources == null || !Boolean.TRUE.equals(resources.subscribe())
          || subscriptions.size() >= maxSubscriptions
          || !subscriptions.add(uri)) {
        return;
      }
      try {
        client.subscribeResource(new McpSchema.SubscribeRequest(uri));
      } catch (RuntimeException ex) {
        subscriptions.remove(uri);
        LOGGER.debug(
            "Unable to subscribe to remote MCP resource {} for connection {}",
            uri,
            connectionId
        );
      }
    }

    private void touch() {
      lastAccessedAt = Instant.now();
    }

    private void close() {
      if (!closed) {
        closed = true;
        client.closeGracefully();
      }
    }

    private String sessionKey() {
      return sessionKey;
    }

    private String connectionId() {
      return connectionId;
    }

    private McpSyncClient client() {
      return client;
    }

    private Instant lastAccessedAt() {
      return lastAccessedAt;
    }

    private boolean closed() {
      return closed;
    }
  }

  private static final class PendingChanges {

    private final EnumSet<McpGatewayEventType> eventTypes =
        EnumSet.noneOf(McpGatewayEventType.class);

    private boolean scheduled;

    private synchronized boolean add(final McpGatewayEventType eventType) {
      eventTypes.add(eventType);
      if (scheduled) {
        return false;
      }
      scheduled = true;
      return true;
    }

    private synchronized Set<McpGatewayEventType> drain() {
      if (eventTypes.isEmpty()) {
        scheduled = false;
        return Set.of();
      }
      Set<McpGatewayEventType> drained = Set.copyOf(eventTypes);
      eventTypes.clear();
      return drained;
    }
  }
}
