package org.simplepoint.plugin.ai.knowledge.service.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.knowledge.api.entity.AiKnowledgeBase;
import org.simplepoint.plugin.ai.knowledge.api.entity.AiKnowledgeDocument;
import org.simplepoint.plugin.ai.knowledge.api.model.AiKnowledgeDocumentStatus;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeBaseRepository;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeChunkRepository;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeDocumentRepository;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeIndexJobRepository;
import org.simplepoint.plugin.ai.knowledge.api.vo.AiKnowledgeIndexJob;
import org.simplepoint.plugin.ai.knowledge.service.support.KnowledgeDocumentExtractor.ExtractedDocument;

class KnowledgeIndexCoordinatorTest {

  private AiKnowledgeBaseRepository knowledgeBaseRepository;

  private AiKnowledgeDocumentRepository documentRepository;

  private AiKnowledgeChunkRepository chunkRepository;

  private AiKnowledgeIndexJobRepository jobRepository;

  private KnowledgeIndexCoordinator coordinator;

  @BeforeEach
  void setUp() {
    knowledgeBaseRepository = mock(AiKnowledgeBaseRepository.class);
    documentRepository = mock(AiKnowledgeDocumentRepository.class);
    chunkRepository = mock(AiKnowledgeChunkRepository.class);
    jobRepository = mock(AiKnowledgeIndexJobRepository.class);
    coordinator = new KnowledgeIndexCoordinator(
        knowledgeBaseRepository,
        documentRepository,
        chunkRepository,
        jobRepository
    );
  }

  @Test
  void saveAndEnqueue_persistsPendingDocumentAndDurableJob() {
    AiKnowledgeDocument document = document(AiKnowledgeDocumentStatus.PROCESSING);
    when(documentRepository.save(document)).thenAnswer(invocation -> {
      document.setId("doc-1");
      return document;
    });

    AiKnowledgeDocument result = coordinator.saveAndEnqueue(document);

    assertThat(result.getStatus()).isEqualTo(AiKnowledgeDocumentStatus.PENDING);
    verify(jobRepository).enqueue(
        "doc-1",
        "kb-1",
        AiResourceScope.TENANT,
        "tenant-1",
        false
    );
  }

  @Test
  void complete_doesNotPublishChunksAfterJobGenerationChanges() {
    AiKnowledgeIndexJob job = job(false);
    AiKnowledgeDocument document = document(AiKnowledgeDocumentStatus.PROCESSING);
    AiKnowledgeBase knowledgeBase = knowledgeBase();
    KnowledgeIndexPreparation preparation = new KnowledgeIndexPreparation(
        "doc-1", "kb-1", List.of(), null, null
    );
    when(documentRepository.findActiveById("doc-1")).thenReturn(Optional.of(document));
    when(knowledgeBaseRepository.findActiveById("kb-1")).thenReturn(Optional.of(knowledgeBase));
    when(jobRepository.complete(job)).thenReturn(false);

    boolean completed = coordinator.complete(job, preparation);

    assertThat(completed).isFalse();
    verify(chunkRepository, never()).replaceDocumentChunks(any(), any());
  }

  @Test
  void terminalReindexFailure_keepsPreviousChunksAvailable() {
    AiKnowledgeIndexJob job = job(true);
    AiKnowledgeDocument document = document(AiKnowledgeDocumentStatus.REINDEXING);
    document.setChunkCount(7);
    when(jobRepository.fail(job, true, Duration.ofSeconds(10), "provider unavailable"))
        .thenReturn(true);
    when(documentRepository.findActiveById("doc-1")).thenReturn(Optional.of(document));

    boolean recorded = coordinator.fail(
        job,
        true,
        Duration.ofSeconds(10),
        "provider unavailable"
    );

    assertThat(recorded).isTrue();
    assertThat(document.getStatus()).isEqualTo(AiKnowledgeDocumentStatus.REINDEX_FAILED);
    assertThat(document.getChunkCount()).isEqualTo(7);
    verify(chunkRepository, never()).deleteByDocumentId("doc-1");
  }

  @Test
  void complete_persistsWorkerExtractionWithPublishedChunks() {
    AiKnowledgeIndexJob job = job(false);
    AiKnowledgeDocument document = document(AiKnowledgeDocumentStatus.PROCESSING);
    AiKnowledgeBase knowledgeBase = knowledgeBase();
    ExtractedDocument extraction = new ExtractedDocument(
        "source.md", "text/markdown", 16L, "parsed knowledge"
    );
    KnowledgeIndexPreparation preparation = new KnowledgeIndexPreparation(
        "doc-1", "kb-1", List.of(), null, extraction
    );
    when(documentRepository.findActiveById("doc-1")).thenReturn(Optional.of(document));
    when(knowledgeBaseRepository.findActiveById("kb-1")).thenReturn(Optional.of(knowledgeBase));
    when(jobRepository.complete(job)).thenReturn(true);

    boolean completed = coordinator.complete(job, preparation);

    assertThat(completed).isTrue();
    assertThat(document.getFileName()).isEqualTo("source.md");
    assertThat(document.getMimeType()).isEqualTo("text/markdown");
    assertThat(document.getExtractedText()).isEqualTo("parsed knowledge");
    assertThat(document.getContentHash()).hasSize(64);
    assertThat(document.getStatus()).isEqualTo(AiKnowledgeDocumentStatus.READY);
  }

  private static AiKnowledgeBase knowledgeBase() {
    AiKnowledgeBase knowledgeBase = new AiKnowledgeBase();
    knowledgeBase.setId("kb-1");
    knowledgeBase.setScopeType(AiResourceScope.TENANT);
    knowledgeBase.setTenantId("tenant-1");
    knowledgeBase.setEnabled(true);
    return knowledgeBase;
  }

  private static AiKnowledgeDocument document(final AiKnowledgeDocumentStatus status) {
    AiKnowledgeDocument document = new AiKnowledgeDocument();
    document.setId("doc-1");
    document.setKnowledgeBaseId("kb-1");
    document.setScopeType(AiResourceScope.TENANT);
    document.setTenantId("tenant-1");
    document.setStatus(status);
    return document;
  }

  private static AiKnowledgeIndexJob job(final boolean preserveExistingIndex) {
    return new AiKnowledgeIndexJob(
        "doc-1",
        "kb-1",
        AiResourceScope.TENANT,
        "tenant-1",
        1L,
        1,
        preserveExistingIndex,
        "worker-1"
    );
  }
}
