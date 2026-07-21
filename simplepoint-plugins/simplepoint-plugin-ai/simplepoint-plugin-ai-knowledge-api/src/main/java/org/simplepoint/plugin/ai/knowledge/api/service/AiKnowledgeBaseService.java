package org.simplepoint.plugin.ai.knowledge.api.service;

import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.ai.knowledge.api.entity.AiKnowledgeBase;
import org.simplepoint.plugin.ai.knowledge.api.vo.AiKnowledgeRetrievalRequest;
import org.simplepoint.plugin.ai.knowledge.api.vo.AiKnowledgeRetrievalResult;

/**
 * Knowledge base configuration and retrieval service.
 */
public interface AiKnowledgeBaseService extends BaseService<AiKnowledgeBase, String> {

  /** Retrieves ranked chunks from one owned knowledge base. */
  AiKnowledgeRetrievalResult retrieve(String knowledgeBaseId, AiKnowledgeRetrievalRequest request);
}
