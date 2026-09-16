package org.simplepoint.plugin.ai.knowledge.service.index;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import org.simplepoint.plugin.ai.knowledge.api.entity.AiKnowledgeBase;
import org.simplepoint.plugin.ai.knowledge.api.entity.AiKnowledgeDocument;
import org.simplepoint.plugin.ai.knowledge.api.model.AiKnowledgeDocumentStatus;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeBaseRepository;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeChunkRepository;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeDocumentRepository;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeIndexJobRepository;
import org.simplepoint.plugin.ai.knowledge.api.vo.AiKnowledgeIndexJob;
import org.simplepoint.plugin.ai.knowledge.service.support.KnowledgeDocumentExtractor.ExtractedDocument;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates document state, durable queue state and atomic index replacement. */
@Component
public class KnowledgeIndexCoordinator {

  private final AiKnowledgeBaseRepository knowledgeBaseRepository;

  private final AiKnowledgeDocumentRepository documentRepository;

  private final AiKnowledgeChunkRepository chunkRepository;

  private final AiKnowledgeIndexJobRepository jobRepository;

  /** Creates the index transaction coordinator. */
  public KnowledgeIndexCoordinator(
      final AiKnowledgeBaseRepository knowledgeBaseRepository,
      final AiKnowledgeDocumentRepository documentRepository,
      final AiKnowledgeChunkRepository chunkRepository,
      final AiKnowledgeIndexJobRepository jobRepository
  ) {
    this.knowledgeBaseRepository = knowledgeBaseRepository;
    this.documentRepository = documentRepository;
    this.chunkRepository = chunkRepository;
    this.jobRepository = jobRepository;
  }

  /** Persists a new document and its first index job in one transaction. */
  @Transactional(rollbackFor = Exception.class)
  public AiKnowledgeDocument saveAndEnqueue(final AiKnowledgeDocument document) {
    document.setStatus(AiKnowledgeDocumentStatus.PENDING);
    document.setChunkCount(0);
    document.setProcessedAt(null);
    document.setErrorMessage(null);
    AiKnowledgeDocument saved = documentRepository.save(document);
    enqueue(saved, false);
    return saved;
  }

  /** Advances the job generation so repeated reindex requests remain idempotent. */
  @Transactional(rollbackFor = Exception.class)
  public AiKnowledgeDocument reindex(final AiKnowledgeDocument document) {
    boolean preserveExisting = hasUsableIndex(document.getStatus());
    document.setStatus(preserveExisting
        ? AiKnowledgeDocumentStatus.REINDEXING
        : AiKnowledgeDocumentStatus.PENDING);
    if (!preserveExisting) {
      document.setChunkCount(0);
      document.setProcessedAt(null);
    }
    document.setErrorMessage(null);
    AiKnowledgeDocument saved = documentRepository.save(document);
    enqueue(saved, preserveExisting);
    return saved;
  }

  /** Marks the leased generation as actively processing. */
  @Transactional(rollbackFor = Exception.class)
  public boolean markProcessing(final AiKnowledgeIndexJob job) {
    if (!jobRepository.isClaimed(job)) {
      return false;
    }
    AiKnowledgeDocument document = documentRepository.findActiveById(job.documentId())
        .orElse(null);
    if (document == null || !matches(job, document)) {
      jobRepository.complete(job);
      return false;
    }
    document.setStatus(job.preserveExistingIndex()
        ? AiKnowledgeDocumentStatus.REINDEXING
        : AiKnowledgeDocumentStatus.PROCESSING);
    document.setErrorMessage(null);
    documentRepository.save(document);
    return true;
  }

  /** Commits prepared chunks only if this worker still owns the claimed generation. */
  @Transactional(rollbackFor = Exception.class)
  public boolean complete(
      final AiKnowledgeIndexJob job,
      final KnowledgeIndexPreparation preparation
  ) {
    if (!Objects.equals(job.documentId(), preparation.documentId())
        || !Objects.equals(job.knowledgeBaseId(), preparation.knowledgeBaseId())) {
      throw new IllegalStateException("索引任务与索引结果不匹配");
    }
    AiKnowledgeDocument document = documentRepository.findActiveById(job.documentId())
        .orElse(null);
    AiKnowledgeBase knowledgeBase = knowledgeBaseRepository
        .findActiveById(job.knowledgeBaseId())
        .orElse(null);
    if (document == null || knowledgeBase == null || !matches(job, document)) {
      jobRepository.complete(job);
      return false;
    }
    if (!jobRepository.complete(job)) {
      return false;
    }
    Integer dimensions = preparation.embeddingDimensions();
    if (dimensions != null) {
      if (knowledgeBase.getEmbeddingDimensions() == null) {
        knowledgeBase.setEmbeddingDimensions(dimensions);
        knowledgeBaseRepository.save(knowledgeBase);
      } else if (!knowledgeBase.getEmbeddingDimensions().equals(dimensions)) {
        throw new IllegalStateException("模型实际返回维度与知识库配置不一致");
      }
    }
    chunkRepository.replaceDocumentChunks(document.getId(), preparation.chunks());
    applyExtraction(document, preparation.extraction());
    document.setStatus(AiKnowledgeDocumentStatus.READY);
    document.setChunkCount(preparation.chunks().size());
    document.setProcessedAt(Instant.now());
    document.setErrorMessage(null);
    documentRepository.save(document);
    return true;
  }

  /** Records retry state or terminal failure for the claimed generation. */
  @Transactional(rollbackFor = Exception.class)
  public boolean fail(
      final AiKnowledgeIndexJob job,
      final boolean terminal,
      final Duration retryDelay,
      final String errorMessage
  ) {
    if (!jobRepository.fail(job, terminal, retryDelay, errorMessage)) {
      return false;
    }
    AiKnowledgeDocument document = documentRepository.findActiveById(job.documentId())
        .orElse(null);
    if (document == null || !matches(job, document)) {
      return false;
    }
    if (terminal) {
      document.setStatus(job.preserveExistingIndex()
          ? AiKnowledgeDocumentStatus.REINDEX_FAILED
          : AiKnowledgeDocumentStatus.FAILED);
      if (!job.preserveExistingIndex()) {
        chunkRepository.deleteByDocumentId(document.getId());
        document.setChunkCount(0);
      }
      document.setProcessedAt(Instant.now());
    } else {
      document.setStatus(job.preserveExistingIndex()
          ? AiKnowledgeDocumentStatus.REINDEXING
          : AiKnowledgeDocumentStatus.PENDING);
    }
    document.setErrorMessage(truncate(errorMessage));
    documentRepository.save(document);
    return true;
  }

  private void enqueue(
      final AiKnowledgeDocument document,
      final boolean preserveExistingIndex
  ) {
    jobRepository.enqueue(
        document.getId(),
        document.getKnowledgeBaseId(),
        document.getScopeType(),
        document.getTenantId(),
        preserveExistingIndex
    );
  }

  private static boolean matches(
      final AiKnowledgeIndexJob job,
      final AiKnowledgeDocument document
  ) {
    return Objects.equals(job.knowledgeBaseId(), document.getKnowledgeBaseId())
        && job.scopeType() == document.getScopeType()
        && Objects.equals(job.tenantId(), document.getTenantId());
  }

  private static boolean hasUsableIndex(final AiKnowledgeDocumentStatus status) {
    return status == AiKnowledgeDocumentStatus.READY
        || status == AiKnowledgeDocumentStatus.REINDEXING
        || status == AiKnowledgeDocumentStatus.REINDEX_FAILED;
  }

  private static void applyExtraction(
      final AiKnowledgeDocument document,
      final ExtractedDocument extraction
  ) {
    if (extraction == null) {
      return;
    }
    document.setName(truncate(extraction.fileName(), 512));
    document.setFileName(truncate(extraction.fileName(), 512));
    document.setMimeType(truncate(extraction.mimeType(), 256));
    document.setFileSize(extraction.fileSize());
    document.setExtractedText(extraction.content());
    document.setContentHash(sha256(extraction.content()));
  }

  private static String sha256(final String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("当前运行环境不支持 SHA-256", ex);
    }
  }

  private static String truncate(final String message) {
    if (message == null || message.isBlank()) {
      return "未知错误";
    }
    return message.length() <= 2000 ? message : message.substring(0, 2000);
  }

  private static String truncate(final String value, final int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }
}
