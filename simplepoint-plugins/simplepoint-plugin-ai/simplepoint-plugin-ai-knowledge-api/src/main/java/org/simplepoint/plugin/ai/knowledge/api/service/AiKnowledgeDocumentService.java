package org.simplepoint.plugin.ai.knowledge.api.service;

import java.util.Collection;
import java.util.Map;
import org.simplepoint.plugin.ai.knowledge.api.entity.AiKnowledgeDocument;
import org.simplepoint.plugin.ai.knowledge.api.vo.AiKnowledgeTextDocumentRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

/**
 * Knowledge document ingestion service.
 */
public interface AiKnowledgeDocumentService {

  /** Pages active documents inside an owned knowledge base. */
  Page<AiKnowledgeDocument> limit(
      String knowledgeBaseId,
      Map<String, String> attributes,
      Pageable pageable
  );

  /** Uploads, parses and indexes a document. */
  AiKnowledgeDocument upload(String knowledgeBaseId, MultipartFile file, String metadataJson);

  /** Adds and indexes a plain-text document. */
  AiKnowledgeDocument addText(
      String knowledgeBaseId,
      AiKnowledgeTextDocumentRequest request
  );

  /** Rebuilds chunks and vectors for a document. */
  AiKnowledgeDocument reindex(String knowledgeBaseId, String documentId);

  /** Deletes documents and their chunks. */
  void remove(String knowledgeBaseId, Collection<String> documentIds);
}
