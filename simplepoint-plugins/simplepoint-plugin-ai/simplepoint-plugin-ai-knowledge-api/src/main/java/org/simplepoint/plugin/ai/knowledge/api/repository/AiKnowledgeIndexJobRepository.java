package org.simplepoint.plugin.ai.knowledge.api.repository;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.knowledge.api.vo.AiKnowledgeIndexJob;

/** Durable queue contract for knowledge document indexing. */
public interface AiKnowledgeIndexJobRepository {

  /** Inserts a new job or advances the generation of an existing document job. */
  void enqueue(
      String documentId,
      String knowledgeBaseId,
      AiResourceScope scopeType,
      String tenantId,
      boolean preserveExistingIndex
  );

  /** Atomically leases the next available jobs. */
  List<AiKnowledgeIndexJob> claim(String leaseOwner, int limit, Duration leaseDuration);

  /** Extends the lease while a long-running embedding batch is active. */
  boolean renew(AiKnowledgeIndexJob job, Duration leaseDuration);

  /** Returns whether the supplied generation is still owned by this worker. */
  boolean isClaimed(AiKnowledgeIndexJob job);

  /** Deletes the claimed generation before its index is committed. */
  boolean complete(AiKnowledgeIndexJob job);

  /** Releases a claimed generation for retry or records terminal failure. */
  boolean fail(
      AiKnowledgeIndexJob job,
      boolean terminal,
      Duration retryDelay,
      String errorMessage
  );

  /** Releases a lease that could not be submitted to the local executor. */
  void release(AiKnowledgeIndexJob job);

  /** Deletes queued work for documents that are being removed. */
  void deleteByDocumentIds(Collection<String> documentIds);

  /** Deletes queued work for a knowledge base that is being removed. */
  void deleteByKnowledgeBaseId(String knowledgeBaseId);
}
