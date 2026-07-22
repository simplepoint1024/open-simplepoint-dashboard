package org.simplepoint.plugin.ai.knowledge.api.vo;

import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/** A leased durable knowledge-index job. */
public record AiKnowledgeIndexJob(
    String documentId,
    String knowledgeBaseId,
    AiResourceScope scopeType,
    String tenantId,
    long generation,
    int attemptCount,
    boolean preserveExistingIndex,
    String leaseOwner
) {
}
