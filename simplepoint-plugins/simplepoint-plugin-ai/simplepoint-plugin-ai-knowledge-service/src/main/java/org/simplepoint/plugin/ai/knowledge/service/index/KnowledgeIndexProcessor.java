package org.simplepoint.plugin.ai.knowledge.service.index;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.simplepoint.plugin.ai.core.api.service.AiEmbeddingService;
import org.simplepoint.plugin.ai.core.api.vo.AiEmbeddingResult;
import org.simplepoint.plugin.ai.knowledge.api.entity.AiKnowledgeBase;
import org.simplepoint.plugin.ai.knowledge.api.entity.AiKnowledgeDocument;
import org.simplepoint.plugin.ai.knowledge.api.model.AiKnowledgeDocumentSourceType;
import org.simplepoint.plugin.ai.knowledge.api.model.AiKnowledgeRetrievalMode;
import org.simplepoint.plugin.ai.knowledge.api.properties.AiKnowledgeProperties;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeBaseRepository;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeDocumentRepository;
import org.simplepoint.plugin.ai.knowledge.api.vo.AiKnowledgeChunkRecord;
import org.simplepoint.plugin.ai.knowledge.api.vo.AiKnowledgeIndexJob;
import org.simplepoint.plugin.ai.knowledge.service.support.KnowledgeDocumentExtractor;
import org.simplepoint.plugin.ai.knowledge.service.support.KnowledgeDocumentExtractor.ExtractedDocument;
import org.simplepoint.plugin.ai.knowledge.service.support.KnowledgeTextChunker;
import org.simplepoint.plugin.storage.api.model.ObjectStorageSourceContent;
import org.simplepoint.plugin.storage.api.service.ObjectStorageSourceService;
import org.springframework.stereotype.Component;

/** Performs CPU and provider work outside the queue-finalization transaction. */
@Component
public class KnowledgeIndexProcessor {

  private final AiKnowledgeBaseRepository knowledgeBaseRepository;

  private final AiKnowledgeDocumentRepository documentRepository;

  private final AiEmbeddingService embeddingService;

  private final ObjectStorageSourceService objectStorageSourceService;

  private final KnowledgeDocumentExtractor extractor;

  private final KnowledgeTextChunker chunker;

  private final AiKnowledgeProperties properties;

  /** Creates the index processor. */
  public KnowledgeIndexProcessor(
      final AiKnowledgeBaseRepository knowledgeBaseRepository,
      final AiKnowledgeDocumentRepository documentRepository,
      final AiEmbeddingService embeddingService,
      final ObjectStorageSourceService objectStorageSourceService,
      final KnowledgeDocumentExtractor extractor,
      final KnowledgeTextChunker chunker,
      final AiKnowledgeProperties properties
  ) {
    this.knowledgeBaseRepository = knowledgeBaseRepository;
    this.documentRepository = documentRepository;
    this.embeddingService = embeddingService;
    this.objectStorageSourceService = objectStorageSourceService;
    this.extractor = extractor;
    this.chunker = chunker;
    this.properties = properties;
  }

  /** Builds all chunks and embeddings for one claimed job generation. */
  public KnowledgeIndexPreparation prepare(
      final AiKnowledgeIndexJob job,
      final Runnable heartbeat
  ) {
    heartbeat.run();
    AiKnowledgeBase knowledgeBase = knowledgeBaseRepository
        .findActiveById(job.knowledgeBaseId())
        .orElseThrow(() -> new IllegalStateException("知识库已被删除"));
    AiKnowledgeDocument document = documentRepository.findActiveById(job.documentId())
        .orElseThrow(() -> new IllegalStateException("知识库文档已被删除"));
    validateTarget(job, knowledgeBase, document);
    ExtractedDocument extraction = extractIfNeeded(document, heartbeat);
    String extractedText = extraction == null
        ? document.getExtractedText() : extraction.content();
    if (extractedText == null || extractedText.isBlank()) {
      throw new IllegalArgumentException("文档中没有可建立索引的文本");
    }
    List<String> texts = chunker.split(
        extractedText,
        knowledgeBase.getChunkSize(),
        knowledgeBase.getChunkOverlap()
    );
    if (texts.isEmpty()) {
      throw new IllegalArgumentException("文档中没有可建立索引的文本");
    }
    int maxChunks = positive(properties.getMaxChunksPerDocument(), 5000);
    if (texts.size() > maxChunks) {
      throw new IllegalArgumentException("文档分块数超过允许的最大值 " + maxChunks);
    }
    EmbeddingBatch embeddings = createEmbeddings(knowledgeBase, job, texts, heartbeat);
    List<AiKnowledgeChunkRecord> chunks = new ArrayList<>(texts.size());
    for (int index = 0; index < texts.size(); index++) {
      List<Double> embedding = embeddings.vectors() == null
          ? null : embeddings.vectors().get(index);
      String text = texts.get(index);
      chunks.add(new AiKnowledgeChunkRecord(
          UUID.randomUUID().toString(),
          knowledgeBase.getId(),
          document.getId(),
          knowledgeBase.getScopeType(),
          knowledgeBase.getTenantId(),
          index,
          text,
          document.getMetadataJson(),
          text.length(),
          embedding,
          embedding == null ? null : embedding.size()
      ));
    }
    heartbeat.run();
    return new KnowledgeIndexPreparation(
        document.getId(),
        knowledgeBase.getId(),
        List.copyOf(chunks),
        embeddings.dimensions(),
        extraction
    );
  }

  private ExtractedDocument extractIfNeeded(
      final AiKnowledgeDocument document,
      final Runnable heartbeat
  ) {
    if (document.getExtractedText() != null && !document.getExtractedText().isBlank()) {
      return null;
    }
    if (document.getSourceType() != AiKnowledgeDocumentSourceType.UPLOAD) {
      throw new IllegalArgumentException("文档中没有可建立索引的文本");
    }
    String storageObjectId = requireValue(document.getStorageObjectId(), "文档缺少原文件对象");
    String storageTenantId = requireValue(document.getStorageTenantId(), "文档缺少对象存储租户");
    heartbeat.run();
    ObjectStorageSourceContent source = objectStorageSourceService.downloadSource(
        storageObjectId,
        storageTenantId,
        "ai",
        maxUploadBytes()
    );
    heartbeat.run();
    ExtractedDocument extraction = extractor.extract(
        source.fileName() == null ? document.getFileName() : source.fileName(),
        source.contentType(),
        source.content()
    );
    heartbeat.run();
    return extraction;
  }

  private EmbeddingBatch createEmbeddings(
      final AiKnowledgeBase knowledgeBase,
      final AiKnowledgeIndexJob job,
      final List<String> texts,
      final Runnable heartbeat
  ) {
    if (knowledgeBase.getRetrievalMode() == AiKnowledgeRetrievalMode.KEYWORD) {
      return new EmbeddingBatch(null, null);
    }
    List<List<Double>> embeddings = new ArrayList<>(texts.size());
    Integer actualDimensions = null;
    int batchSize = Math.max(1, Math.min(128, properties.getEmbeddingBatchSize()));
    for (int start = 0; start < texts.size(); start += batchSize) {
      heartbeat.run();
      int end = Math.min(start + batchSize, texts.size());
      AiEmbeddingResult result = embeddingService.embedForScope(
          job.scopeType(),
          job.tenantId(),
          knowledgeBase.getEmbeddingModelId(),
          texts.subList(start, end),
          knowledgeBase.getEmbeddingDimensions()
      );
      if (result.dimensions() > properties.getStoredVectorDimensions()) {
        throw new IllegalArgumentException(
            "模型返回 " + result.dimensions() + " 维向量，超过 pgvector 索引上限 "
                + properties.getStoredVectorDimensions()
        );
      }
      if (knowledgeBase.getEmbeddingDimensions() != null
          && !knowledgeBase.getEmbeddingDimensions().equals(result.dimensions())) {
        throw new IllegalStateException("模型实际返回维度与知识库配置不一致");
      }
      if (actualDimensions != null && !actualDimensions.equals(result.dimensions())) {
        throw new IllegalStateException("同一文档的 Embedding 向量维度不一致");
      }
      actualDimensions = result.dimensions();
      embeddings.addAll(result.vectors());
    }
    if (embeddings.size() != texts.size()) {
      throw new IllegalStateException("Embedding 响应数量与文档分块数不一致");
    }
    return new EmbeddingBatch(List.copyOf(embeddings), actualDimensions);
  }

  private static void validateTarget(
      final AiKnowledgeIndexJob job,
      final AiKnowledgeBase knowledgeBase,
      final AiKnowledgeDocument document
  ) {
    if (!Boolean.TRUE.equals(knowledgeBase.getEnabled())) {
      throw new IllegalStateException("知识库未启用");
    }
    if (!Objects.equals(document.getKnowledgeBaseId(), knowledgeBase.getId())
        || job.scopeType() != knowledgeBase.getScopeType()
        || job.scopeType() != document.getScopeType()
        || !Objects.equals(job.tenantId(), knowledgeBase.getTenantId())
        || !Objects.equals(job.tenantId(), document.getTenantId())) {
      throw new IllegalStateException("知识库索引任务作用域不一致");
    }
  }

  private long maxUploadBytes() {
    Long configured = properties.getMaxUploadBytes();
    return configured != null && configured > 0
        ? configured : 20L * 1024L * 1024L;
  }

  private static String requireValue(final String value, final String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(message);
    }
    return value.trim();
  }

  private static int positive(final Integer value, final int fallback) {
    return value != null && value > 0 ? value : fallback;
  }

  private record EmbeddingBatch(List<List<Double>> vectors, Integer dimensions) {
  }
}
