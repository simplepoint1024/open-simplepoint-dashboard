package org.simplepoint.plugin.ai.mcp.repository;

import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpInvocation;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpInvocationRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for MCP invocation metadata.
 */
@Repository
public interface JpaAiMcpInvocationRepository
    extends BaseRepository<AiMcpInvocation, String>,
    AiMcpInvocationRepository {
}
