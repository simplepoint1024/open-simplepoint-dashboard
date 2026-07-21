package org.simplepoint.plugin.ai.knowledge.api.vo;

import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.ai.knowledge.api.model.AiKnowledgeRetrievalMode;

/**
 * Internal hybrid retrieval specification.
 */
public record AiKnowledgeSearchSpec(
    String knowledgeBaseId,
    String query,
    AiKnowledgeRetrievalMode mode,
    List<Double> queryEmbedding,
    Set<String> documentIds,
    int topK,
    double scoreThreshold,
    double vectorWeight,
    double keywordWeight
) {
}
