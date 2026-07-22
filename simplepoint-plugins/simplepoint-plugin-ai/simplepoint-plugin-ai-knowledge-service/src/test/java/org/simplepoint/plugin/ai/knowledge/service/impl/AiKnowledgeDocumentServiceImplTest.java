package org.simplepoint.plugin.ai.knowledge.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.knowledge.api.entity.AiKnowledgeBase;
import org.simplepoint.plugin.ai.knowledge.api.entity.AiKnowledgeDocument;
import org.simplepoint.plugin.ai.knowledge.api.model.AiKnowledgeRetrievalMode;
import org.simplepoint.plugin.ai.knowledge.api.properties.AiKnowledgeProperties;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeBaseRepository;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeChunkRepository;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeDocumentRepository;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeIndexJobRepository;
import org.simplepoint.plugin.ai.knowledge.service.index.KnowledgeIndexCoordinator;
import org.simplepoint.plugin.ai.knowledge.service.support.KnowledgeDocumentExtractor;
import org.simplepoint.plugin.ai.knowledge.service.support.KnowledgeDocumentExtractor.UploadDescriptor;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageObject;
import org.simplepoint.plugin.storage.api.model.ObjectStorageUploadRequest;
import org.simplepoint.plugin.storage.client.service.ObjectStorageRemoteService;
import org.springframework.mock.web.MockMultipartFile;

class AiKnowledgeDocumentServiceImplTest {

  private AiKnowledgeBaseRepository knowledgeBaseRepository;

  private AiKnowledgeDocumentRepository documentRepository;

  private AiKnowledgeChunkRepository chunkRepository;

  private KnowledgeDocumentExtractor extractor;

  private KnowledgeIndexCoordinator indexCoordinator;

  private ObjectStorageRemoteService objectStorageRemoteService;

  private AiKnowledgeDocumentServiceImpl service;

  @BeforeEach
  void setUp() {
    knowledgeBaseRepository = mock(AiKnowledgeBaseRepository.class);
    documentRepository = mock(AiKnowledgeDocumentRepository.class);
    chunkRepository = mock(AiKnowledgeChunkRepository.class);
    extractor = mock(KnowledgeDocumentExtractor.class);
    indexCoordinator = mock(KnowledgeIndexCoordinator.class);
    objectStorageRemoteService = mock(ObjectStorageRemoteService.class);
    service = new AiKnowledgeDocumentServiceImpl(
        knowledgeBaseRepository,
        documentRepository,
        chunkRepository,
        mock(AiKnowledgeIndexJobRepository.class),
        mock(AiScopeAccessPolicy.class),
        extractor,
        indexCoordinator,
        new AiKnowledgeProperties(),
        new ObjectMapper(),
        objectStorageRemoteService
    );
  }

  @Test
  void upload_storesSourceThroughUnifiedObjectStorageClient() {
    final AiKnowledgeBase knowledgeBase = knowledgeBase();
    final MockMultipartFile file = new MockMultipartFile(
        "file",
        "测试文档.md",
        "text/markdown",
        "knowledge content".getBytes(StandardCharsets.UTF_8)
    );
    final ObjectStorageObject storageObject = new ObjectStorageObject();
    storageObject.setId("storage-1");
    storageObject.setTenantId("storage-tenant-1");
    when(knowledgeBaseRepository.findActiveById("kb-1")).thenReturn(Optional.of(knowledgeBase));
    when(extractor.validate(file)).thenReturn(new UploadDescriptor(
        "测试文档.md", "text/markdown", file.getSize()
    ));
    when(objectStorageRemoteService.upload(any(), any())).thenReturn(storageObject);
    when(indexCoordinator.saveAndEnqueue(any())).thenAnswer(invocation -> {
      final AiKnowledgeDocument document = invocation.getArgument(0);
      if (document.getId() == null) {
        document.setId("document-1");
      }
      return document;
    });

    final AiKnowledgeDocument result = service.upload("kb-1", file, null);

    final ArgumentCaptor<ObjectStorageUploadRequest> requestCaptor =
        ArgumentCaptor.forClass(ObjectStorageUploadRequest.class);
    verify(objectStorageRemoteService).upload(any(), requestCaptor.capture());
    assertThat(requestCaptor.getValue().getDirectory())
        .isEqualTo("ai/knowledge-bases/kb-1");
    assertThat(requestCaptor.getValue().getFileName()).isEqualTo("测试文档.md");
    assertThat(requestCaptor.getValue().getSourceServiceName()).isEqualTo("ai");
    assertThat(result.getStorageObjectId()).isEqualTo("storage-1");
    assertThat(result.getStorageTenantId()).isEqualTo("storage-tenant-1");
    assertThat(result.getExtractedText()).isNull();
    verify(indexCoordinator).saveAndEnqueue(any());
    verify(extractor, never()).extract(file);
    verify(objectStorageRemoteService, never()).delete("storage-1");
  }

  @Test
  void remove_deletesReferencedStorageObject() {
    final AiKnowledgeBase knowledgeBase = knowledgeBase();
    final AiKnowledgeDocument document = new AiKnowledgeDocument();
    document.setId("document-1");
    document.setKnowledgeBaseId("kb-1");
    document.setScopeType(AiResourceScope.TENANT);
    document.setTenantId("tenant-1");
    document.setStorageObjectId("storage-1");
    when(knowledgeBaseRepository.findActiveById("kb-1")).thenReturn(Optional.of(knowledgeBase));
    when(documentRepository.findActiveById("document-1")).thenReturn(Optional.of(document));

    service.remove("kb-1", List.of("document-1"));

    verify(chunkRepository).deleteByDocumentId("document-1");
    verify(documentRepository).deleteByIds(List.of("document-1"));
    verify(objectStorageRemoteService).delete("storage-1");
  }

  private static AiKnowledgeBase knowledgeBase() {
    final AiKnowledgeBase knowledgeBase = new AiKnowledgeBase();
    knowledgeBase.setId("kb-1");
    knowledgeBase.setScopeType(AiResourceScope.TENANT);
    knowledgeBase.setTenantId("tenant-1");
    knowledgeBase.setEnabled(true);
    knowledgeBase.setRetrievalMode(AiKnowledgeRetrievalMode.KEYWORD);
    knowledgeBase.setChunkSize(1000);
    knowledgeBase.setChunkOverlap(150);
    return knowledgeBase;
  }
}
