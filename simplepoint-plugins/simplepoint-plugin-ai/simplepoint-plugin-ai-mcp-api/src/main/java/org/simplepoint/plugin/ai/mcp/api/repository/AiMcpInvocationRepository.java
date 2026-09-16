package org.simplepoint.plugin.ai.mcp.api.repository;

import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpInvocation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Repository contract for MCP invocation metadata.
 */
public interface AiMcpInvocationRepository
    extends BaseRepository<AiMcpInvocation, String> {

  /** Pages metadata-only invocation records in one exact owner scope. */
  Page<AiMcpInvocation> findAllActiveByScope(
      AiResourceScope scopeType,
      String tenantId,
      String serverId,
      Pageable pageable
  );
}
