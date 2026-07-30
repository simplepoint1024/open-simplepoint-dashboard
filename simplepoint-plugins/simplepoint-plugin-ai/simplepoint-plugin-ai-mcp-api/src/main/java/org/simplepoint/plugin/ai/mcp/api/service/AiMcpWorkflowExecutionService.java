package org.simplepoint.plugin.ai.mcp.api.service;

import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayPromptGetResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayResourceReadResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallResult;
import org.simplepoint.plugin.ai.mcp.api.model.McpWorkflowPromptGetRequest;
import org.simplepoint.plugin.ai.mcp.api.model.McpWorkflowResourceReadRequest;
import org.simplepoint.plugin.ai.mcp.api.model.McpWorkflowToolCallRequest;

/**
 * Trusted background boundary for immutable Skill-to-MCP execution.
 */
public interface AiMcpWorkflowExecutionService {

  /**
   * Invokes exactly the server, snapshot and Tool pinned by a Skill version.
   */
  McpGatewayToolCallResult callWorkflowTool(
      McpWorkflowToolCallRequest request
  );

  /**
   * Renders exactly the server, snapshot and Prompt pinned by a Skill version.
   */
  McpGatewayPromptGetResult getWorkflowPrompt(
      McpWorkflowPromptGetRequest request
  );

  /**
   * Reads a Resource allowed by an immutable Skill binding.
   */
  McpGatewayResourceReadResult readWorkflowResource(
      McpWorkflowResourceReadRequest request
  );
}
