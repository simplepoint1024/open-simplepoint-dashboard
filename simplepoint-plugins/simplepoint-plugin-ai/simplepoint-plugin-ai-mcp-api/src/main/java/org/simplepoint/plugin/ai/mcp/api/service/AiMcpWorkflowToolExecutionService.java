package org.simplepoint.plugin.ai.mcp.api.service;

import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallResult;
import org.simplepoint.plugin.ai.mcp.api.model.McpWorkflowToolCallRequest;

/**
 * Trusted background boundary for pinned Skill-to-MCP Tool execution.
 */
public interface AiMcpWorkflowToolExecutionService {

  /**
   * Invokes exactly the server, snapshot and Tool persisted by a Skill version.
   */
  McpGatewayToolCallResult callWorkflowTool(
      McpWorkflowToolCallRequest request
  );
}
