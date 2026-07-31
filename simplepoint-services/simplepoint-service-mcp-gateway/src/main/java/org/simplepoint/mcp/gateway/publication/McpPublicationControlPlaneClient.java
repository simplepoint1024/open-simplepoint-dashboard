package org.simplepoint.mcp.gateway.publication;

import java.net.http.HttpClient;
import org.simplepoint.mcp.gateway.config.McpGatewayProperties;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayPromptGetResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayResourceReadResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayUpstreamEvent;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationChangeEvent;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationManifest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationPromptGetRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationResourceReadRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationTaskCreateRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationTaskListRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationTaskRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationToolCallRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpTaskDescriptor;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpTaskListResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpTaskResult;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Private Gateway client for publication manifests and capability execution.
 */
@Component
public class McpPublicationControlPlaneClient {

  private final McpGatewayProperties properties;

  private final RestClient restClient;

  /**
   * Creates the control-plane client.
   */
  public McpPublicationControlPlaneClient(final McpGatewayProperties properties) {
    this.properties = properties;
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(properties.getConnectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.getRequestTimeout());
    this.restClient = RestClient.builder()
        .baseUrl(baseUrl(properties.getControlPlaneBaseUrl()))
        .requestFactory(requestFactory)
        .build();
  }

  /**
   * Loads one active publication manifest.
   */
  public McpPublicationManifest manifest(final String code) {
    try {
      return restClient.get()
          .uri("/internal/mcp/publications/{code}/manifest", code)
          .header(internalHeader(), internalToken())
          .retrieve()
          .body(McpPublicationManifest.class);
    } catch (RestClientResponseException ex) {
      throw failure("load MCP publication manifest", ex);
    }
  }

  /**
   * Calls one published tool through the trusted control plane.
   */
  public McpGatewayToolCallResult callTool(
      final McpPublicationToolCallRequest request
  ) {
    return post(
        "/internal/mcp/publications/tools/call",
        request,
        McpGatewayToolCallResult.class,
        "call published MCP tool"
    );
  }

  /**
   * Creates one durable task-augmented published Tool call.
   */
  public McpTaskDescriptor createTask(
      final McpPublicationTaskCreateRequest request
  ) {
    return post(
        "/internal/mcp/publications/tasks/create",
        request,
        McpTaskDescriptor.class,
        "create published MCP Task"
    );
  }

  /**
   * Returns one authorization-bound MCP Task.
   */
  public McpTaskDescriptor getTask(
      final McpPublicationTaskRequest request
  ) {
    return post(
        "/internal/mcp/publications/tasks/get",
        request,
        McpTaskDescriptor.class,
        "get published MCP Task"
    );
  }

  /**
   * Lists authorization-bound MCP Tasks.
   */
  public McpTaskListResult listTasks(
      final McpPublicationTaskListRequest request
  ) {
    return post(
        "/internal/mcp/publications/tasks/list",
        request,
        McpTaskListResult.class,
        "list published MCP Tasks"
    );
  }

  /**
   * Returns one Task's underlying result projection.
   */
  public McpTaskResult taskResult(
      final McpPublicationTaskRequest request
  ) {
    return post(
        "/internal/mcp/publications/tasks/result",
        request,
        McpTaskResult.class,
        "get published MCP Task result"
    );
  }

  /**
   * Cancels one non-terminal Task.
   */
  public McpTaskDescriptor cancelTask(
      final McpPublicationTaskRequest request
  ) {
    return post(
        "/internal/mcp/publications/tasks/cancel",
        request,
        McpTaskDescriptor.class,
        "cancel published MCP Task"
    );
  }

  /**
   * Reads one published resource through the trusted control plane.
   */
  public McpGatewayResourceReadResult readResource(
      final McpPublicationResourceReadRequest request
  ) {
    return post(
        "/internal/mcp/publications/resources/read",
        request,
        McpGatewayResourceReadResult.class,
        "read published MCP resource"
    );
  }

  /**
   * Renders one published prompt through the trusted control plane.
   */
  public McpGatewayPromptGetResult getPrompt(
      final McpPublicationPromptGetRequest request
  ) {
    return post(
        "/internal/mcp/publications/prompts/get",
        request,
        McpGatewayPromptGetResult.class,
        "get published MCP prompt"
    );
  }

  /**
   * Persists an upstream notification and resolves affected publications.
   */
  public McpPublicationChangeEvent upstreamEvent(
      final McpGatewayUpstreamEvent event
  ) {
    return post(
        "/internal/mcp/publications/upstream-events",
        event,
        McpPublicationChangeEvent.class,
        "persist upstream MCP event"
    );
  }

  private <T> T post(
      final String path,
      final Object request,
      final Class<T> responseType,
      final String operation
  ) {
    try {
      return restClient.post()
          .uri(path)
          .header(internalHeader(), internalToken())
          .body(request)
          .retrieve()
          .body(responseType);
    } catch (RestClientResponseException ex) {
      throw failure(operation, ex);
    }
  }

  private String internalHeader() {
    String value = properties.getInternalHeaderName();
    if (!StringUtils.hasText(value) || value.contains("\r") || value.contains("\n")) {
      throw new IllegalStateException("MCP Gateway internal header is not configured safely");
    }
    return value.trim();
  }

  private String internalToken() {
    String value = properties.getInternalToken();
    if (!StringUtils.hasText(value)) {
      throw new IllegalStateException("MCP Gateway internal token is not configured");
    }
    return value.trim();
  }

  private static String baseUrl(final String value) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalStateException("MCP control-plane base URL is not configured");
    }
    return value.trim().replaceAll("/+$", "");
  }

  private static McpControlPlaneException failure(
      final String operation,
      final RestClientResponseException exception
  ) {
    return new McpControlPlaneException(
        "Unable to " + operation + ": HTTP " + exception.getStatusCode().value(),
        exception.getStatusCode().value(),
        exception
    );
  }

  /**
   * Preserves the private control-plane status for JSON-RPC error mapping.
   */
  public static class McpControlPlaneException extends IllegalStateException {

    private final int statusCode;

    /**
     * Creates one control-plane failure.
     */
    public McpControlPlaneException(
        final String message,
        final int statusCode,
        final Throwable cause
    ) {
      super(message, cause);
      this.statusCode = statusCode;
    }

    /**
     * Returns the upstream HTTP status.
     */
    public int statusCode() {
      return statusCode;
    }
  }
}
