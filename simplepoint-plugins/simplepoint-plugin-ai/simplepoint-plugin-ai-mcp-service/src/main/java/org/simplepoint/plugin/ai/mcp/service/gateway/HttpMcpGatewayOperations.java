package org.simplepoint.plugin.ai.mcp.service.gateway;

import java.net.http.HttpClient;
import org.simplepoint.plugin.ai.mcp.api.constants.AiMcpPaths;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayCancellationRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayConnection;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayDiscoveryResult;
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
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayUpstreamException;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayWorkflowPromptGetRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayWorkflowResourceReadRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayWorkflowToolCallRequest;
import org.simplepoint.plugin.ai.mcp.api.properties.AiMcpProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * AI control-plane client for the independent MCP Gateway.
 */
@Service
public class HttpMcpGatewayOperations implements McpGatewayOperations {

  private final RestClient restClient;

  private final AiMcpProperties properties;

  /**
   * Creates the Gateway client.
   *
   * @param properties MCP control-plane properties
   */
  public HttpMcpGatewayOperations(final AiMcpProperties properties) {
    this.properties = properties;
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(properties.getGatewayRequestTimeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.getGatewayRequestTimeout());
    this.restClient = RestClient.builder()
        .baseUrl(normalizeBaseUrl(properties.getGatewayBaseUrl()))
        .requestFactory(requestFactory)
        .build();
  }

  @Override
  public McpGatewayStatus status() {
    try {
      return restClient.get()
          .uri(AiMcpPaths.INTERNAL_STATUS)
          .header(internalHeader(), internalToken())
          .retrieve()
          .body(McpGatewayStatus.class);
    } catch (RestClientResponseException ex) {
      throw gatewayFailure("read MCP Gateway status", ex);
    }
  }

  @Override
  public McpGatewayDiscoveryResult discover(final McpGatewayConnection connection) {
    try {
      return restClient.post()
          .uri(AiMcpPaths.INTERNAL_DISCOVER)
          .header(internalHeader(), internalToken())
          .body(connection)
          .retrieve()
          .body(McpGatewayDiscoveryResult.class);
    } catch (RestClientResponseException ex) {
      throw gatewayFailure("discover MCP capabilities", ex);
    }
  }

  @Override
  public McpGatewayToolCallResult callTool(final McpGatewayToolCallRequest request) {
    try {
      return restClient.post()
          .uri(AiMcpPaths.INTERNAL_CALL_TOOL)
          .header(internalHeader(), internalToken())
          .body(request)
          .retrieve()
          .body(McpGatewayToolCallResult.class);
    } catch (RestClientResponseException ex) {
      throw gatewayFailure("call MCP tool", ex);
    }
  }

  @Override
  public void cancel(final McpGatewayCancellationRequest request) {
    try {
      restClient.post()
          .uri(AiMcpPaths.INTERNAL_CANCEL_OPERATION)
          .header(internalHeader(), internalToken())
          .body(request)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientResponseException ex) {
      throw gatewayFailure("cancel MCP operation", ex);
    }
  }

  @Override
  public McpGatewayToolCallResult callWorkflowTool(
      final McpGatewayWorkflowToolCallRequest request
  ) {
    try {
      return restClient.post()
          .uri(AiMcpPaths.INTERNAL_CALL_WORKFLOW_TOOL)
          .header(internalHeader(), internalToken())
          .body(request)
          .retrieve()
          .body(McpGatewayToolCallResult.class);
    } catch (RestClientResponseException ex) {
      throw gatewayFailure("call capability-authorized MCP workflow Tool", ex);
    }
  }

  @Override
  public McpGatewayPromptGetResult getWorkflowPrompt(
      final McpGatewayWorkflowPromptGetRequest request
  ) {
    return post(
        AiMcpPaths.INTERNAL_GET_WORKFLOW_PROMPT,
        request,
        McpGatewayPromptGetResult.class,
        "get capability-authorized MCP workflow Prompt"
    );
  }

  @Override
  public McpGatewayResourceReadResult readWorkflowResource(
      final McpGatewayWorkflowResourceReadRequest request
  ) {
    return post(
        AiMcpPaths.INTERNAL_READ_WORKFLOW_RESOURCE,
        request,
        McpGatewayResourceReadResult.class,
        "read capability-authorized MCP workflow Resource"
    );
  }

  @Override
  public McpGatewayResourceReadResult readResource(
      final McpGatewayResourceReadRequest request
  ) {
    return post(AiMcpPaths.INTERNAL_READ_RESOURCE, request,
        McpGatewayResourceReadResult.class, "read MCP resource");
  }

  @Override
  public McpGatewayPromptGetResult getPrompt(
      final McpGatewayPromptGetRequest request
  ) {
    return post(AiMcpPaths.INTERNAL_GET_PROMPT, request,
        McpGatewayPromptGetResult.class, "get MCP prompt");
  }

  @Override
  public McpGatewayOauthDiscoveryResult discoverOauth(
      final McpGatewayOauthDiscoveryRequest request
  ) {
    return post(AiMcpPaths.INTERNAL_OAUTH_DISCOVER, request,
        McpGatewayOauthDiscoveryResult.class, "discover MCP OAuth metadata");
  }

  @Override
  public McpGatewayOauthRegistrationResult registerOauthClient(
      final McpGatewayOauthRegistrationRequest request
  ) {
    return post(AiMcpPaths.INTERNAL_OAUTH_REGISTER, request,
        McpGatewayOauthRegistrationResult.class, "register MCP OAuth client");
  }

  @Override
  public McpGatewayOauthTokenResult exchangeOauthToken(
      final McpGatewayOauthTokenRequest request
  ) {
    return post(AiMcpPaths.INTERNAL_OAUTH_TOKEN, request,
        McpGatewayOauthTokenResult.class, "exchange MCP OAuth token");
  }

  private <T> T post(
      final String path,
      final Object body,
      final Class<T> responseType,
      final String operation
  ) {
    try {
      return restClient.post()
          .uri(path)
          .header(internalHeader(), internalToken())
          .body(body)
          .retrieve()
          .body(responseType);
    } catch (RestClientResponseException ex) {
      throw gatewayFailure(operation, ex);
    }
  }

  private String internalHeader() {
    String value = properties.getGatewayInternalHeader();
    if (!StringUtils.hasText(value)
        || value.contains("\r")
        || value.contains("\n")) {
      throw new IllegalStateException("MCP Gateway internal header is not configured safely");
    }
    return value.trim();
  }

  private String internalToken() {
    String value = properties.getGatewayInternalToken();
    if (!StringUtils.hasText(value)) {
      throw new IllegalStateException(
          "simplepoint.ai.mcp.gateway-internal-token must be configured"
      );
    }
    return value.trim();
  }

  private static McpGatewayUpstreamException gatewayFailure(
      final String operation,
      final RestClientResponseException exception
  ) {
    int statusCode = exception.getStatusCode().value();
    return new McpGatewayUpstreamException(
        "Unable to " + operation + " through MCP Gateway: HTTP "
            + statusCode,
        statusCode,
        exception
    );
  }

  private static String normalizeBaseUrl(final String value) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalStateException("MCP Gateway base URL must be configured");
    }
    return value.trim().replaceAll("/+$", "");
  }
}
