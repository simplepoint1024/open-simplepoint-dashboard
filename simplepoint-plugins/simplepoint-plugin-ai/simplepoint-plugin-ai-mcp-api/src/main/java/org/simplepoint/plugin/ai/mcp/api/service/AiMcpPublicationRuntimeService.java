package org.simplepoint.plugin.ai.mcp.api.service;

import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayPromptGetResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayResourceReadResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayUpstreamEvent;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationChangeEvent;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationManifest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationPromptGetRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationResourceReadRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationToolCallRequest;

/**
 * Trusted runtime boundary used only by the independent Gateway.
 */
public interface AiMcpPublicationRuntimeService {

  /**
   * Resolves the immutable active manifest for one publication.
   */
  McpPublicationManifest manifest(String code);

  /**
   * Calls one tool from the publication's pinned upstream snapshot.
   */
  McpGatewayToolCallResult callPublishedTool(McpPublicationToolCallRequest request);

  /**
   * Reads one resource from the publication's pinned upstream snapshot.
   */
  McpGatewayResourceReadResult readPublishedResource(
      McpPublicationResourceReadRequest request
  );

  /**
   * Renders one prompt from the publication's pinned upstream snapshot.
   */
  McpGatewayPromptGetResult getPublishedPrompt(McpPublicationPromptGetRequest request);

  /**
   * Persists an upstream list change and resolves affected active publications.
   */
  McpPublicationChangeEvent handleUpstreamEvent(McpGatewayUpstreamEvent event);
}
