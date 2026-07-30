package org.simplepoint.plugin.ai.agent.api.vo;

import java.time.Instant;
import org.simplepoint.plugin.ai.agent.api.model.AgentMemoryScope;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/**
 * One durable, scope-isolated Agent memory.
 *
 * @param id memory identifier
 * @param agentId owning Agent
 * @param agentVersionId source Agent version
 * @param sourceExecutionId source execution
 * @param scopeType platform or tenant scope
 * @param tenantId tenant identifier when tenant-scoped
 * @param memoryScope memory sharing boundary
 * @param subjectId authenticated subject boundary
 * @param content bounded episodic memory content
 * @param contentHash SHA-256 of content
 * @param score retrieval relevance, or {@code null} for history queries
 * @param createdAt creation time
 * @param expiresAt retention deadline
 */
public record AiAgentMemory(
    String id,
    String agentId,
    String agentVersionId,
    String sourceExecutionId,
    AiResourceScope scopeType,
    String tenantId,
    AgentMemoryScope memoryScope,
    String subjectId,
    String content,
    String contentHash,
    Double score,
    Instant createdAt,
    Instant expiresAt
) {
}
