package org.simplepoint.plugin.ai.mcp.api.service;

import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationTaskCreateRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationTaskListRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationTaskRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpTaskDescriptor;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpTaskListResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpTaskResult;

/**
 * MCP Tasks compatibility layer over durable platform execution records.
 */
public interface AiMcpTaskService {

  /**
   * Creates one task-augmented Tool call.
   */
  McpTaskDescriptor create(McpPublicationTaskCreateRequest request);

  /**
   * Finds one authorization-bound Task.
   */
  McpTaskDescriptor find(McpPublicationTaskRequest request);

  /**
   * Lists authorization-bound Tasks.
   */
  McpTaskListResult findAll(McpPublicationTaskListRequest request);

  /**
   * Returns Task state and its exact terminal Tool result.
   */
  McpTaskResult result(McpPublicationTaskRequest request);

  /**
   * Cooperatively cancels one non-terminal Task.
   */
  McpTaskDescriptor cancel(McpPublicationTaskRequest request);
}
