package org.simplepoint.mcp.gateway.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.mcp.gateway.config.McpGatewayProperties;
import org.simplepoint.mcp.gateway.event.McpCancellationRegistry;
import org.simplepoint.mcp.gateway.event.McpUpstreamEventCoordinator;
import org.simplepoint.mcp.gateway.security.McpOauthClientMetadataDocument;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayConnection;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayConnectionKind;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayDiscoveryResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayPromptGetRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayPromptGetResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayStatus;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallResult;

class DefaultRemoteMcpGatewayOperationsTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private HttpServer server;

  private DefaultRemoteMcpGatewayOperations operations;

  private McpGatewayConnection connection;

  private AtomicInteger initializeCount;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    initializeCount = new AtomicInteger();
    server.createContext("/mcp", this::handleMcp);
    server.start();
    McpGatewayProperties properties = new McpGatewayProperties();
    operations = new DefaultRemoteMcpGatewayOperations(
        new McpEndpointPolicy(),
        properties,
        objectMapper,
        new RemoteMcpOauthClient(
            new McpEndpointPolicy(),
            properties,
            objectMapper,
            new McpOauthClientMetadataDocument(properties)
        ),
        mock(McpUpstreamEventCoordinator.class),
        mock(McpCancellationRegistry.class)
    );
    connection = new McpGatewayConnection(
        "test-server",
        "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp",
        "Bearer test-token",
        true
    );
  }

  @AfterEach
  void tearDown() {
    operations.close();
    server.stop(0);
  }

  @Test
  void reportsStableProtocolAndSdkBaseline() {
    McpGatewayStatus status = operations.status();

    assertEquals("UP", status.status());
    assertEquals("2025-11-25", status.protocolBaseline());
    assertEquals("2.0.0", status.sdkVersion());
  }

  @Test
  void managedRuntimeConnectionFailsClosedWithoutMutualTls() {
    McpGatewayConnection managed = new McpGatewayConnection(
        "managed-server",
        "https://127.0.0.1:" + server.getAddress().getPort()
            + "/mcp/v1/workloads/workload-1",
        null,
        true,
        McpGatewayConnectionKind.MANAGED_RUNTIME,
        "lease-1",
        7
    );

    assertThrows(IllegalStateException.class, () -> operations.discover(managed));
  }

  @Test
  void discoversToolsUsingStableProtocol() {
    McpGatewayDiscoveryResult result = operations.discover(connection);

    assertEquals("2025-11-25", result.protocolVersion());
    assertEquals("simplepoint-test-mcp", result.serverName());
    assertEquals(2, result.tools().size());
    assertEquals("greet", result.tools().getFirst().name());
    assertEquals("version", result.tools().getLast().name());
    assertEquals("object", result.tools().getFirst().inputSchema().get("type"));
  }

  @Test
  void reusesInitializedSessionForTheSameConnection() {
    operations.discover(connection);
    operations.discover(connection);

    assertEquals(1, initializeCount.get());
  }

  @Test
  void validatesArgumentsAndCallsTool() {
    McpGatewayToolCallResult result = operations.callTool(
        new McpGatewayToolCallRequest(
            connection,
            "greet",
            Map.of("name", "SimplePoint"),
            Map.of(),
            null
        )
    );

    assertFalse(result.error());
    assertEquals("text", result.content().getFirst().get("type"));
    assertEquals(
        "Hello SimplePoint",
        result.content().getFirst().get("text")
    );
    assertEquals(
        "Hello SimplePoint",
        result.structuredContent() instanceof Map<?, ?> content
            ? content.get("greeting")
            : null
    );
  }

  @Test
  void preservesPromptRoleAndContentDiscriminator() {
    McpGatewayPromptGetResult result = operations.getPrompt(
        new McpGatewayPromptGetRequest(
            connection,
            "welcome",
            Map.of("name", "SimplePoint"),
            Map.of(),
            null
        )
    );

    assertEquals("user", result.messages().getFirst().get("role"));
    assertEquals(
        "text",
        ((Map<?, ?>) result.messages().getFirst().get("content")).get("type")
    );
  }

  @Test
  void rejectsArgumentsThatDoNotMatchInputSchema() {
    McpGatewayToolCallRequest request = new McpGatewayToolCallRequest(
        connection,
        "greet",
        Map.of("unexpected", true),
        Map.of(),
        null
    );

    assertThrows(IllegalArgumentException.class, () -> operations.callTool(request));
  }

  private void handleMcp(final HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }
    JsonNode request = objectMapper.readTree(exchange.getRequestBody());
    String method = request.path("method").asText();
    if ("notifications/initialized".equals(method)) {
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      return;
    }
    JsonNode id = request.get("id");
    Map<String, Object> result = switch (method) {
      case "initialize" -> {
        initializeCount.incrementAndGet();
        yield Map.of(
            "protocolVersion", "2025-11-25",
            "capabilities", Map.of(
                "tools", Map.of("listChanged", false),
                "prompts", Map.of("listChanged", false)
            ),
            "serverInfo", Map.of(
                "name", "simplepoint-test-mcp",
                "title", "SimplePoint Test MCP",
                "version", "1.0.0"
            )
        );
      }
      case "tools/list" -> {
        String cursor = request.path("params").path("cursor").asText();
        if ("tools-page-2".equals(cursor)) {
          yield Map.of("tools", java.util.List.of(Map.of(
              "name", "version",
              "title", "Version",
              "description", "Returns the version",
              "inputSchema", Map.of(
                  "type", "object",
                  "additionalProperties", false
              )
          )));
        }
        yield Map.of(
            "tools",
            java.util.List.of(Map.of(
                "name", "greet",
                "title", "Greet",
                "description", "Returns a greeting",
                "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of("name", Map.of("type", "string")),
                    "required", java.util.List.of("name"),
                    "additionalProperties", false
                ),
                "outputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "greeting",
                        Map.of("type", "string")
                    ),
                    "required", java.util.List.of("greeting")
                )
            )),
            "nextCursor",
            "tools-page-2"
        );
      }
      case "tools/call" -> {
        String name = request.path("params").path("arguments").path("name").asText();
        yield Map.of(
            "content", java.util.List.of(Map.of(
                "type", "text",
                "text", "Hello " + name
            )),
            "isError", false,
            "structuredContent", Map.of("greeting", "Hello " + name)
        );
      }
      case "prompts/list" -> Map.of(
          "prompts", java.util.List.of(Map.of(
              "name", "welcome",
              "title", "Welcome",
              "description", "Returns a welcome prompt",
              "arguments", java.util.List.of(Map.of(
                  "name", "name",
                  "required", true
              ))
          ))
      );
      case "prompts/get" -> Map.of(
          "description", "Welcome prompt",
          "messages", java.util.List.of(Map.of(
              "role", "user",
              "content", Map.of(
                  "type", "text",
                  "text", "Welcome "
                      + request.path("params").path("arguments")
                          .path("name").asText()
              )
          ))
      );
      default -> throw new IllegalArgumentException("Unexpected MCP method: " + method);
    };
    byte[] response = objectMapper.writeValueAsBytes(Map.of(
        "jsonrpc", "2.0",
        "id", objectMapper.convertValue(id, Object.class),
        "result", result
    ));
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, response.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(response);
    }
  }
}
