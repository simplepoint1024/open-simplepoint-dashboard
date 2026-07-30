package org.simplepoint.plugin.ai.agent.api.vo;

import org.simplepoint.plugin.ai.agent.api.model.AgentMemoryScope;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/**
 * Exact isolation and ranking contract for long-term memory retrieval.
 *
 * @param agentId owning Agent
 * @param scopeType platform or tenant scope
 * @param tenantId tenant identifier when tenant-scoped
 * @param memoryScope memory sharing boundary
 * @param subjectId authenticated subject boundary
 * @param excludedExecutionId current execution, which cannot retrieve itself
 * @param query normalized retrieval query
 * @param topK maximum returned memories
 * @param scoreThreshold minimum keyword relevance
 */
public record AiAgentMemorySearchSpec(
    String agentId,
    AiResourceScope scopeType,
    String tenantId,
    AgentMemoryScope memoryScope,
    String subjectId,
    String excludedExecutionId,
    String query,
    int topK,
    double scoreThreshold
) {
}
