package org.simplepoint.plugin.ai.agent.api.vo;

import java.time.Instant;
import org.simplepoint.plugin.ai.agent.api.model.AgentMemoryScope;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/**
 * Idempotent durable memory write prepared by the Agent Runtime.
 *
 * @param id generated memory identifier
 * @param agentId owning Agent
 * @param agentVersionId source Agent version
 * @param sourceExecutionId unique source execution
 * @param scopeType platform or tenant scope
 * @param tenantId tenant identifier when tenant-scoped
 * @param memoryScope memory sharing boundary
 * @param subjectId authenticated subject boundary
 * @param content bounded episodic memory content
 * @param contentHash SHA-256 of content
 * @param expiresAt retention deadline
 * @param maximumEntries maximum retained entries in the exact memory boundary
 */
public record AiAgentMemoryWrite(
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
    Instant expiresAt,
    int maximumEntries
) {
}
