package org.simplepoint.plugin.ai.knowledge.api.vo;

import java.util.List;
import org.simplepoint.plugin.ai.knowledge.api.model.AiKnowledgeRetrievalMode;

/**
 * Ranked response for a knowledge retrieval request.
 *
 * @param query normalized query
 * @param mode effective retrieval mode
 * @param hits ranked chunks
 */
public record AiKnowledgeRetrievalResult(
    String query,
    AiKnowledgeRetrievalMode mode,
    List<AiKnowledgeSearchHit> hits
) {
}
