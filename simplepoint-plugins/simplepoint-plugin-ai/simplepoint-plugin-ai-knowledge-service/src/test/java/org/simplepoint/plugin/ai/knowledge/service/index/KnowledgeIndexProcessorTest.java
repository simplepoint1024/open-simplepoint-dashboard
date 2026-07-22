package org.simplepoint.plugin.ai.knowledge.service.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.api.service.AiEmbeddingService;
import org.simplepoint.plugin.ai.core.api.vo.AiEmbeddingResult;
import org.simplepoint.plugin.ai.knowledge.api.entity.AiKnowledgeBase;
import org.simplepoint.plugin.ai.knowledge.api.entity.AiKnowledgeDocument;
import org.simplepoint.plugin.ai.knowledge.api.model.AiKnowledgeDocumentSourceType;
import org.simplepoint.plugin.ai.knowledge.api.model.AiKnowledgeRetrievalMode;
import org.simplepoint.plugin.ai.knowledge.api.properties.AiKnowledgeProperties;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeBaseRepository;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeDocumentRepository;
import org.simplepoint.plugin.ai.knowledge.api.vo.AiKnowledgeIndexJob;
import org.simplepoint.plugin.ai.knowledge.service.support.KnowledgeDocumentExtractor;
import org.simplepoint.plugin.ai.knowledge.service.support.KnowledgeDocumentExtractor.ExtractedDocument;
import org.simplepoint.plugin.ai.knowledge.service.support.KnowledgeTextChunker;
import org.simplepoint.plugin.storage.api.model.ObjectStorageSourceContent;
import org.simplepoint.plugin.storage.api.service.ObjectStorageSourceService;

class KnowledgeIndexProcessorTest {

  private AiKnowledgeBaseRepository knowledgeBaseRepository;

  private AiKnowledgeDocumentRepository documentRepository;

  private AiEmbeddingService embeddingService;

  private ObjectStorageSourceService objectStorageSourceService;

  private KnowledgeDocumentExtractor extractor;

  private KnowledgeTextChunker chunker;

  private AiKnowledgeProperties properties;

  private KnowledgeIndexProcessor processor;

  @BeforeEach
  void setUp() {
    knowledgeBaseRepository = mock(AiKnowledgeBaseRepository.class);
    documentRepository = mock(AiKnowledgeDocumentRepository.class);
    embeddingService = mock(AiEmbeddingService.class);
    objectStorageSourceService = mock(ObjectStorageSourceService.class);
    extractor = mock(KnowledgeDocumentExtractor.class);
    chunker = mock(KnowledgeTextChunker.class);
    properties = new AiKnowledgeProperties();
    processor = new KnowledgeIndexProcessor(
        knowledgeBaseRepository,
        documentRepository,
        embeddingService,
        objectStorageSourceService,
        extractor,
        chunker,
        properties
    );
  }

  @Test
  void keywordJob_buildsChunksWithoutEmbeddingCall() {
    AiKnowledgeBase knowledgeBase = knowledgeBase(AiKnowledgeRetrievalMode.KEYWORD);
    AiKnowledgeDocument document = document();
    AiKnowledgeIndexJob job = job(false);
    when(knowledgeBaseRepository.findActiveById("kb-1")).thenReturn(Optional.of(knowledgeBase));
    when(documentRepository.findActiveById("doc-1")).thenReturn(Optional.of(document));
    when(chunker.split("first second", 1000, 150)).thenReturn(List.of("first", "second"));
    AtomicInteger heartbeats = new AtomicInteger();

    KnowledgeIndexPreparation result = processor.prepare(job, heartbeats::incrementAndGet);

    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().getFirst().scopeType()).isEqualTo(AiResourceScope.TENANT);
    assertThat(result.embeddingDimensions()).isNull();
    assertThat(heartbeats).hasValue(2);
    verifyNoInteractions(embeddingService);
  }

  @Test
  void vectorJob_invokesEmbeddingWithDurableJobScope() {
    AiKnowledgeBase knowledgeBase = knowledgeBase(AiKnowledgeRetrievalMode.VECTOR);
    knowledgeBase.setEmbeddingModelId("embedding-1");
    knowledgeBase.setEmbeddingDimensions(2);
    AiKnowledgeDocument document = document();
    AiKnowledgeIndexJob job = job(false);
    when(knowledgeBaseRepository.findActiveById("kb-1")).thenReturn(Optional.of(knowledgeBase));
    when(documentRepository.findActiveById("doc-1")).thenReturn(Optional.of(document));
    when(chunker.split("first second", 1000, 150)).thenReturn(List.of("first", "second"));
    when(embeddingService.embedForScope(
        AiResourceScope.TENANT,
        "tenant-1",
        "embedding-1",
        List.of("first", "second"),
        2
    )).thenReturn(new AiEmbeddingResult(
        "remote-embedding",
        2,
        List.of(List.of(0.1D, 0.2D), List.of(0.3D, 0.4D))
    ));

    KnowledgeIndexPreparation result = processor.prepare(job, () -> {});

    assertThat(result.embeddingDimensions()).isEqualTo(2);
    assertThat(result.chunks().getFirst().embedding()).containsExactly(0.1D, 0.2D);
    verify(embeddingService).embedForScope(
        AiResourceScope.TENANT,
        "tenant-1",
        "embedding-1",
        List.of("first", "second"),
        2
    );
  }

  @Test
  void uploadJob_downloadsAndExtractsSourceInWorker() {
    AiKnowledgeDocument document = document();
    document.setExtractedText(null);
    document.setSourceType(AiKnowledgeDocumentSourceType.UPLOAD);
    document.setStorageObjectId("storage-1");
    document.setStorageTenantId("storage-tenant-1");
    AiKnowledgeIndexJob job = job(false);
    byte[] content = "raw source".getBytes(StandardCharsets.UTF_8);
    AiKnowledgeBase knowledgeBase = knowledgeBase(AiKnowledgeRetrievalMode.KEYWORD);
    when(knowledgeBaseRepository.findActiveById("kb-1")).thenReturn(Optional.of(knowledgeBase));
    when(documentRepository.findActiveById("doc-1")).thenReturn(Optional.of(document));
    when(objectStorageSourceService.downloadSource(
        "storage-1", "storage-tenant-1", "ai", 20L * 1024L * 1024L
    )).thenReturn(new ObjectStorageSourceContent(
        content, "source.md", "text/markdown", content.length
    ));
    when(extractor.extract("source.md", "text/markdown", content))
        .thenReturn(new ExtractedDocument(
            "source.md", "text/markdown", content.length, "parsed knowledge"
        ));
    when(chunker.split("parsed knowledge", 1000, 150))
        .thenReturn(List.of("parsed knowledge"));
    AtomicInteger heartbeats = new AtomicInteger();

    KnowledgeIndexPreparation result = processor.prepare(job, heartbeats::incrementAndGet);

    assertThat(result.extraction()).isNotNull();
    assertThat(result.extraction().content()).isEqualTo("parsed knowledge");
    assertThat(result.chunks()).hasSize(1);
    assertThat(heartbeats).hasValue(5);
    verify(objectStorageSourceService).downloadSource(
        "storage-1", "storage-tenant-1", "ai", 20L * 1024L * 1024L
    );
  }

  private static AiKnowledgeBase knowledgeBase(final AiKnowledgeRetrievalMode mode) {
    AiKnowledgeBase knowledgeBase = new AiKnowledgeBase();
    knowledgeBase.setId("kb-1");
    knowledgeBase.setScopeType(AiResourceScope.TENANT);
    knowledgeBase.setTenantId("tenant-1");
    knowledgeBase.setEnabled(true);
    knowledgeBase.setRetrievalMode(mode);
    knowledgeBase.setChunkSize(1000);
    knowledgeBase.setChunkOverlap(150);
    return knowledgeBase;
  }

  private static AiKnowledgeDocument document() {
    AiKnowledgeDocument document = new AiKnowledgeDocument();
    document.setId("doc-1");
    document.setKnowledgeBaseId("kb-1");
    document.setScopeType(AiResourceScope.TENANT);
    document.setTenantId("tenant-1");
    document.setExtractedText("first second");
    document.setSourceType(AiKnowledgeDocumentSourceType.TEXT);
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
