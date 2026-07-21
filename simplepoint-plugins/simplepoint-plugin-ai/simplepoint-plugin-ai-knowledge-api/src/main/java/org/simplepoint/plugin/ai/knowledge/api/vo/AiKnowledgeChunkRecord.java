package org.simplepoint.plugin.ai.knowledge.api.vo;

import java.util.List;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/**
 * Chunk persistence payload.
 */
public record AiKnowledgeChunkRecord(
    String id,
    String knowledgeBaseId,
    String documentId,
    AiResourceScope scopeType,
    String tenantId,
    int chunkIndex,
    String content,
    String metadataJson,
    int characterCount,
    List<Double> embedding,
    Integer embeddingDimensions
) {
}
