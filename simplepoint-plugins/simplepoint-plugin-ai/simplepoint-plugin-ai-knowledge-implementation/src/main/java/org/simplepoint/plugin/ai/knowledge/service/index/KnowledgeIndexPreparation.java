package org.simplepoint.plugin.ai.knowledge.service.index;

import java.util.List;
import org.simplepoint.plugin.ai.knowledge.api.vo.AiKnowledgeChunkRecord;
import org.simplepoint.plugin.ai.knowledge.service.support.KnowledgeDocumentExtractor.ExtractedDocument;

/** Fully prepared chunks waiting for one atomic database commit. */
record KnowledgeIndexPreparation(
    String documentId,
    String knowledgeBaseId,
    List<AiKnowledgeChunkRecord> chunks,
    Integer embeddingDimensions,
    ExtractedDocument extraction
) {
}
