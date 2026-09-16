package org.simplepoint.plugin.ai.mcp.api.service;

import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpProviderConnection;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpServerDefinition;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayDiscoveryResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayPromptGetResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayResourceReadResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPromptDescriptor;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpResourceDescriptor;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpResourceTemplateDescriptor;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpToolDescriptor;
import org.simplepoint.plugin.ai.mcp.api.model.McpCapabilitySnapshotDetails;
import org.simplepoint.plugin.ai.mcp.api.model.McpCapabilitySnapshotSummary;
import org.simplepoint.plugin.ai.mcp.api.model.McpOauthAuthorizationStart;
import org.simplepoint.plugin.ai.mcp.api.model.McpOauthCallbackCommand;
import org.simplepoint.plugin.ai.mcp.api.model.McpPromptGetCommand;
import org.simplepoint.plugin.ai.mcp.api.model.McpResourceReadCommand;
import org.simplepoint.plugin.ai.mcp.api.model.McpToolCallCommand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Control-plane service for remote MCP Server registration and operations.
 */
public interface AiMcpServerDefinitionService
    extends BaseService<AiMcpServerDefinition, String> {

  /**
   * Finds an active MCP server visible in the current scope.
   *
   * @param id server identifier
   * @return visible server
   */
  Optional<AiMcpServerDefinition> findActiveById(String id);

  /**
   * Discovers and snapshots a remote server's MCP capabilities.
   *
   * @param id server identifier
   * @return negotiated capabilities
   */
  McpGatewayDiscoveryResult discover(String id);

  /**
   * Pages immutable capability snapshot history for one visible MCP server.
   */
  Page<McpCapabilitySnapshotSummary> listSnapshots(String id, Pageable pageable);

  /**
   * Returns one decoded immutable capability snapshot.
   */
  McpCapabilitySnapshotDetails getSnapshot(String id, String snapshotId);

  /**
   * Returns one immutable capability snapshot for durable background work.
   * The caller supplies its persisted owner scope because no request
   * authorization context exists on worker threads.
   *
   * @param id server identifier
   * @param snapshotId capability snapshot identifier
   * @param invocationScope scope that owns the background work
   * @param invocationTenantId tenant id that owns the background work
   * @return decoded immutable snapshot
   */
  McpCapabilitySnapshotDetails getSnapshotForScope(
      String id,
      String snapshotId,
      AiResourceScope invocationScope,
      String invocationTenantId
  );

  /**
   * Lists tools from a server's active immutable snapshot.
   *
   * @param id server identifier
   * @return discovered tools
   */
  List<McpToolDescriptor> listTools(String id);

  /**
   * Lists resources from the active immutable snapshot.
   */
  List<McpResourceDescriptor> listResources(String id);

  /**
   * Lists resource templates from the active immutable snapshot.
   */
  List<McpResourceTemplateDescriptor> listResourceTemplates(String id);

  /**
   * Reads one snapshotted resource through the Gateway.
   */
  McpGatewayResourceReadResult readResource(String id, McpResourceReadCommand command);

  /**
   * Lists prompts from the active immutable snapshot.
   */
  List<McpPromptDescriptor> listPrompts(String id);

  /**
   * Renders one snapshotted prompt through the Gateway.
   */
  McpGatewayPromptGetResult getPrompt(String id, McpPromptGetCommand command);

  /**
   * Invokes one snapshotted tool through the independent Gateway.
   *
   * @param id server identifier
   * @param command tool invocation command
   * @return tool result
   */
  McpGatewayToolCallResult callTool(String id, McpToolCallCommand command);

  /**
   * Starts Authorization Code + PKCE for an OAuth protected MCP server.
   */
  McpOauthAuthorizationStart startOauthAuthorization(String id);

  /**
   * Completes a one-time OAuth authorization transaction.
   */
  AiMcpServerDefinition completeOauthAuthorization(McpOauthCallbackCommand command);

  /** Returns the current user's provider connection without token material. */
  Optional<AiMcpProviderConnection> currentProviderConnection(String id);

  /** Deletes token material for the current user's provider connection. */
  AiMcpProviderConnection disconnectProvider(String id);
}
