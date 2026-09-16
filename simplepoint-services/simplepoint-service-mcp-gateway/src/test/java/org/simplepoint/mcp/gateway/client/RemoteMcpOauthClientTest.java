package org.simplepoint.mcp.gateway.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.mcp.gateway.config.McpGatewayProperties;
import org.simplepoint.mcp.gateway.security.McpOauthClientMetadataDocument;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthDiscoveryRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthDiscoveryResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthTokenRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthTokenResult;

class RemoteMcpOauthClientTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private final AtomicReference<String> tokenRequestBody = new AtomicReference<>();

  private HttpServer server;

  private RemoteMcpOauthClient client;

  private String origin;

  private boolean advertiseS256;

  private boolean advertiseClientMetadata;

  @BeforeEach
  void setUp() throws IOException {
    advertiseS256 = true;
    advertiseClientMetadata = true;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/.well-known/oauth-protected-resource/mcp/demo",
        this::protectedResourceMetadata
    );
    server.createContext(
        "/.well-known/oauth-authorization-server",
        this::authorizationServerMetadata
    );
    server.createContext("/oauth2/token", this::token);
    server.start();
    origin = "http://127.0.0.1:" + server.getAddress().getPort();
    McpGatewayProperties properties = new McpGatewayProperties();
    properties.setAllowInsecureOauthEndpoints(true);
    properties.setOauthClientMetadataDocumentUri(
        "https://gateway.example"
            + McpOauthClientMetadataDocument.PATH
    );
    properties.setOauthClientRedirectUris(List.of(
        "https://gateway.example/ai/workbench/mcp-servers"
    ));
    client = new RemoteMcpOauthClient(
        new McpEndpointPolicy(),
        properties,
        objectMapper,
        new McpOauthClientMetadataDocument(properties)
    );
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void discoversPathSpecificProtectedResourceAndPkceMetadata() {
    McpGatewayOauthDiscoveryResult result = client.discover(
        new McpGatewayOauthDiscoveryRequest(origin + "/mcp/demo", true)
    );

    assertEquals(origin + "/mcp/demo", result.resource());
    assertEquals(origin, result.authorizationServer());
    assertEquals(origin + "/oauth2/authorize", result.authorizationEndpoint());
    assertEquals(origin + "/oauth2/token", result.tokenEndpoint());
    assertTrue(result.clientIdMetadataDocumentSupported());
    assertEquals(
        "https://gateway.example" + McpOauthClientMetadataDocument.PATH,
        result.clientIdMetadataDocumentUri()
    );
    assertEquals(List.of("mcp.invoke"), result.scopesSupported());
    assertTrue(result.codeChallengeMethodsSupported().contains("S256"));
  }

  @Test
  void rejectsAuthorizationServerWithoutPkceS256() {
    advertiseS256 = false;

    assertThrows(
        IllegalArgumentException.class,
        () -> client.discover(
            new McpGatewayOauthDiscoveryRequest(origin + "/mcp/demo", true)
        )
    );
  }

  @Test
  void sendsResourceIndicatorDuringAuthorizationCodeExchange() {
    McpGatewayOauthTokenResult result = client.exchange(
        new McpGatewayOauthTokenRequest(
            origin + "/oauth2/token",
            "authorization_code",
            "mcp-client",
            null,
            "none",
            "authorization-code",
            "pkce-verifier",
            "http://localhost:8080/callback",
            null,
            origin + "/mcp/demo",
            null,
            true
        )
    );

    assertEquals("access-token", result.accessToken());
    assertTrue(tokenRequestBody.get().contains(
        "resource=" + encoded(origin + "/mcp/demo")
    ));
    assertTrue(tokenRequestBody.get().contains("code_verifier=pkce-verifier"));
  }

  @Test
  void exchangesAuthorizationCodeWithoutOptionalResourceIndicator() {
    McpGatewayOauthTokenResult result = client.exchange(
        new McpGatewayOauthTokenRequest(
            origin + "/oauth2/token",
            "authorization_code",
            "github-client",
            "github-secret",
            "client_secret_post",
            "authorization-code",
            "pkce-verifier",
            "http://127.0.0.1:8080/ai/workbench/mcp-servers",
            null,
            null,
            null,
            true
        )
    );

    assertEquals("access-token", result.accessToken());
    assertFalse(tokenRequestBody.get().contains("resource="));
    assertTrue(tokenRequestBody.get().contains("client_secret=github-secret"));
  }

  private void protectedResourceMetadata(final HttpExchange exchange)
      throws IOException {
    json(exchange, Map.of(
        "resource", origin + "/mcp/demo",
        "authorization_servers", List.of(origin),
        "scopes_supported", List.of("mcp.invoke")
    ));
  }

  private void authorizationServerMetadata(final HttpExchange exchange)
      throws IOException {
    Map<String, Object> metadata = new java.util.LinkedHashMap<>();
    metadata.put("issuer", origin);
    metadata.put("authorization_endpoint", origin + "/oauth2/authorize");
    metadata.put("token_endpoint", origin + "/oauth2/token");
    metadata.put(
        "code_challenge_methods_supported",
        advertiseS256 ? List.of("S256") : List.of("plain")
    );
    metadata.put(
        "client_id_metadata_document_supported",
        advertiseClientMetadata
    );
    json(exchange, metadata);
  }

  private void token(final HttpExchange exchange) throws IOException {
    tokenRequestBody.set(new String(
        exchange.getRequestBody().readAllBytes(),
        StandardCharsets.UTF_8
    ));
    json(exchange, Map.of(
        "access_token", "access-token",
        "token_type", "Bearer",
        "expires_in", 300,
        "refresh_token", "refresh-token",
        "scope", "mcp.invoke"
    ));
  }

  private void json(
      final HttpExchange exchange,
      final Map<String, Object> body
  ) throws IOException {
    byte[] response = objectMapper.writeValueAsBytes(body);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, response.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(response);
    }
  }

  private static String encoded(final String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)
        .replace("+", "%20");
  }
}
