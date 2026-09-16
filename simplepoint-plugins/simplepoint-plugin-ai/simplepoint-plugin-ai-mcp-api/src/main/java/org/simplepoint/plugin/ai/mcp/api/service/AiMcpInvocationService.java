package org.simplepoint.plugin.ai.mcp.api.service;

import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpInvocation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Read-only access to scope-filtered MCP invocation metadata. */
public interface AiMcpInvocationService {

  /** Pages the active scope, optionally restricted to one MCP Server. */
  Page<AiMcpInvocation> findAll(String serverId, Pageable pageable);
}
