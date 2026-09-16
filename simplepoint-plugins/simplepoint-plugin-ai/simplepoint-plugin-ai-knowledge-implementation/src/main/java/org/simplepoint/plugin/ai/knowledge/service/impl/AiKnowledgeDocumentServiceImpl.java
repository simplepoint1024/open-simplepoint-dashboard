package org.simplepoint.plugin.ai.knowledge.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.knowledge.api.entity.AiKnowledgeBase;
import org.simplepoint.plugin.ai.knowledge.api.entity.AiKnowledgeDocument;
import org.simplepoint.plugin.ai.knowledge.api.model.AiKnowledgeDocumentSourceType;
import org.simplepoint.plugin.ai.knowledge.api.model.AiKnowledgeDocumentStatus;
import org.simplepoint.plugin.ai.knowledge.api.properties.AiKnowledgeProperties;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeBaseRepository;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeChunkRepository;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeDocumentRepository;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeIndexJobRepository;
import org.simplepoint.plugin.ai.knowledge.api.service.AiKnowledgeDocumentService;
import org.simplepoint.plugin.ai.knowledge.api.vo.AiKnowledgeTextDocumentRequest;
import org.simplepoint.plugin.ai.knowledge.service.index.KnowledgeIndexCoordinator;
import org.simplepoint.plugin.ai.knowledge.service.support.KnowledgeDocumentExtractor;
import org.simplepoint.plugin.ai.knowledge.service.support.KnowledgeDocumentExtractor.UploadDescriptor;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageObject;
import org.simplepoint.plugin.storage.api.model.ObjectStorageUploadRequest;
import org.simplepoint.plugin.storage.client.service.ObjectStorageRemoteService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Persists document sources before enqueueing durable background extraction and indexing.
 */
@Service
public class AiKnowledgeDocumentServiceImpl implements AiKnowledgeDocumentService {

  private final AiKnowledgeBaseRepository knowledgeBaseRepository;

  private final AiKnowledgeDocumentRepository documentRepository;

  private final AiKnowledgeChunkRepository chunkRepository;

  private final AiKnowledgeIndexJobRepository indexJobRepository;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  private final KnowledgeDocumentExtractor extractor;

  private final KnowledgeIndexCoordinator indexCoordinator;

  private final AiKnowledgeProperties properties;

  private final ObjectMapper objectMapper;

  private final ObjectStorageRemoteService objectStorageRemoteService;

  /**
   * Creates the document service.
   */
  public AiKnowledgeDocumentServiceImpl(
      final AiKnowledgeBaseRepository knowledgeBaseRepository,
      final AiKnowledgeDocumentRepository documentRepository,
      final AiKnowledgeChunkRepository chunkRepository,
      final AiKnowledgeIndexJobRepository indexJobRepository,
      final AiScopeAccessPolicy scopeAccessPolicy,
      final KnowledgeDocumentExtractor extractor,
      final KnowledgeIndexCoordinator indexCoordinator,
      final AiKnowledgeProperties properties,
      final ObjectMapper objectMapper,
      final ObjectStorageRemoteService objectStorageRemoteService
  ) {
    this.knowledgeBaseRepository = knowledgeBaseRepository;
    this.documentRepository = documentRepository;
    this.chunkRepository = chunkRepository;
    this.indexJobRepository = indexJobRepository;
    this.scopeAccessPolicy = scopeAccessPolicy;
    this.extractor = extractor;
    this.indexCoordinator = indexCoordinator;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.objectStorageRemoteService = objectStorageRemoteService;
  }

  /** {@inheritDoc} */
  @Override
  public Page<AiKnowledgeDocument> limit(
      final String knowledgeBaseId,
      final Map<String, String> attributes,
      final Pageable pageable
  ) {
    AiKnowledgeBase knowledgeBase = findOwnedKnowledgeBase(knowledgeBaseId);
    Map<String, String> normalized = new LinkedHashMap<>();
    if (attributes != null) {
      normalized.putAll(attributes);
    }
    normalized.put("knowledgeBaseId", knowledgeBase.getId());
    normalized.put("deletedAt", "is:null");
    normalizeLikeQuery(normalized, "name");
    normalizeLikeQuery(normalized, "fileName");
    return documentRepository.limit(normalized, pageable);
  }

  /** {@inheritDoc} */
  @Override
  public AiKnowledgeDocument upload(
      final String knowledgeBaseId,
      final MultipartFile file,
      final String metadataJson
  ) {
    AiKnowledgeBase knowledgeBase = findEnabledKnowledgeBase(knowledgeBaseId);
    String metadata = validateMetadataJson(metadataJson);
    UploadDescriptor upload = extractor.validate(file);
    ObjectStorageObject storageObject = objectStorageRemoteService.upload(
        file,
        knowledgeUploadRequest(knowledgeBase, upload.fileName())
    );
    if (storageObject == null) {
      throw new IllegalStateException("统一对象存储上传未返回对象元信息");
    }
    String storageObjectId = requireValue(storageObject.getId(), "统一对象存储上传未返回对象 ID");
    try {
      String storageTenantId = requireValue(
          storageObject.getTenantId(),
          "统一对象存储上传未返回对象租户"
      );
      AiKnowledgeDocument document = newDocument(
          knowledgeBase,
          upload.fileName(),
          upload.fileName(),
          upload.mimeType(),
          upload.fileSize(),
          storageObjectId,
          storageTenantId,
          AiKnowledgeDocumentSourceType.UPLOAD,
          null,
          metadata
      );
      return indexCoordinator.saveAndEnqueue(document);
    } catch (RuntimeException ex) {
      cleanupStorageObject(storageObjectId, ex);
      throw ex;
    }
  }

  /** {@inheritDoc} */
  @Override
  public AiKnowledgeDocument addText(
      final String knowledgeBaseId,
      final AiKnowledgeTextDocumentRequest request
  ) {
    AiKnowledgeBase knowledgeBase = findEnabledKnowledgeBase(knowledgeBaseId);
    if (request == null) {
      throw new IllegalArgumentException("文本文档请求不能为空");
    }
    String name = requireValue(request.getName(), "文档名称不能为空");
    String content = requireValue(request.getContent(), "文档内容不能为空");
    if (content.length() > properties.getMaxExtractedCharacters()) {
      throw new IllegalArgumentException("文档文本超过允许的最大字符数");
    }
    AiKnowledgeDocument document = newDocument(
        knowledgeBase,
        name,
        null,
        "text/plain; charset=UTF-8",
        (long) content.getBytes(StandardCharsets.UTF_8).length,
        null,
        null,
        AiKnowledgeDocumentSourceType.TEXT,
        content,
        validateMetadataJson(request.getMetadataJson())
    );
    return indexCoordinator.saveAndEnqueue(document);
  }

  /** {@inheritDoc} */
  @Override
  public AiKnowledgeDocument reindex(
      final String knowledgeBaseId,
      final String documentId
  ) {
    AiKnowledgeBase knowledgeBase = findEnabledKnowledgeBase(knowledgeBaseId);
    AiKnowledgeDocument document = findOwnedDocument(knowledgeBase, documentId);
    return indexCoordinator.reindex(document);
  }

  /** {@inheritDoc} */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public void remove(
      final String knowledgeBaseId,
      final Collection<String> documentIds
  ) {
    AiKnowledgeBase knowledgeBase = findOwnedKnowledgeBase(knowledgeBaseId);
    if (documentIds == null || documentIds.isEmpty()) {
      return;
    }
    List<AiKnowledgeDocument> documents = documentIds.stream()
        .map(id -> findOwnedDocument(knowledgeBase, id))
        .toList();
    indexJobRepository.deleteByDocumentIds(
        documents.stream().map(AiKnowledgeDocument::getId).toList()
    );
    documents.forEach(document -> chunkRepository.deleteByDocumentId(document.getId()));
    documentRepository.deleteByIds(documents.stream().map(AiKnowledgeDocument::getId).toList());
    documents.stream()
        .map(AiKnowledgeDocument::getStorageObjectId)
        .map(AiKnowledgeDocumentServiceImpl::trimToNull)
        .filter(java.util.Objects::nonNull)
        .forEach(objectStorageRemoteService::delete);
  }

  private AiKnowledgeDocument newDocument(
      final AiKnowledgeBase knowledgeBase,
      final String name,
      final String fileName,
      final String mimeType,
      final Long fileSize,
      final String storageObjectId,
      final String storageTenantId,
      final AiKnowledgeDocumentSourceType sourceType,
      final String content,
      final String metadataJson
  ) {
    AiKnowledgeDocument document = new AiKnowledgeDocument();
    document.setKnowledgeBaseId(knowledgeBase.getId());
    document.setScopeType(knowledgeBase.getScopeType());
    document.setTenantId(knowledgeBase.getTenantId());
    document.setName(truncate(requireValue(name, "文档名称不能为空"), 512));
    document.setFileName(truncate(fileName, 512));
    document.setMimeType(truncate(mimeType, 256));
    document.setFileSize(fileSize);
    document.setStorageObjectId(storageObjectId);
    document.setStorageTenantId(storageTenantId);
    document.setContentHash(content == null ? null : sha256(content));
    document.setSourceType(sourceType);
    document.setStatus(AiKnowledgeDocumentStatus.PENDING);
    document.setChunkCount(0);
    document.setMetadataJson(metadataJson);
    document.setExtractedText(content);
    return document;
  }

  private ObjectStorageUploadRequest knowledgeUploadRequest(
      final AiKnowledgeBase knowledgeBase,
      final String fileName
  ) {
    ObjectStorageUploadRequest request = new ObjectStorageUploadRequest();
    request.setDirectory("ai/knowledge-bases/" + knowledgeBase.getId());
    request.setFileName(fileName);
    request.setSourceServiceName("ai");
    return request;
  }

  private void cleanupStorageObject(final String storageObjectId, final RuntimeException cause) {
    try {
      objectStorageRemoteService.delete(storageObjectId);
    } catch (RuntimeException cleanupException) {
      cause.addSuppressed(cleanupException);
    }
  }

  private AiKnowledgeBase findEnabledKnowledgeBase(final String id) {
    AiKnowledgeBase knowledgeBase = findOwnedKnowledgeBase(id);
    if (!Boolean.TRUE.equals(knowledgeBase.getEnabled())) {
      throw new IllegalStateException("知识库未启用");
    }
    return knowledgeBase;
  }

  private AiKnowledgeBase findOwnedKnowledgeBase(final String id) {
    AiKnowledgeBase knowledgeBase = knowledgeBaseRepository
        .findActiveById(requireValue(id, "知识库 ID 不能为空"))
        .orElseThrow(() -> new IllegalArgumentException("知识库不存在"));
    scopeAccessPolicy.assertCanManageOwnedResource(
        knowledgeBase.getScopeType(), knowledgeBase.getTenantId()
    );
    return knowledgeBase;
  }

  private AiKnowledgeDocument findOwnedDocument(
      final AiKnowledgeBase knowledgeBase,
      final String documentId
  ) {
    AiKnowledgeDocument document = documentRepository
        .findActiveById(requireValue(documentId, "文档 ID 不能为空"))
        .orElseThrow(() -> new IllegalArgumentException("文档不存在"));
    if (!knowledgeBase.getId().equals(document.getKnowledgeBaseId())) {
      throw new IllegalArgumentException("文档不属于当前知识库");
    }
    scopeAccessPolicy.assertCanManageOwnedResource(
        document.getScopeType(), document.getTenantId()
    );
    return document;
  }

  private String validateMetadataJson(final String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return null;
    }
    try {
      JsonNode node = objectMapper.readTree(normalized);
      if (!node.isObject()) {
        throw new IllegalArgumentException("扩展元数据必须是 JSON 对象");
      }
      return objectMapper.writeValueAsString(node);
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      throw new IllegalArgumentException("扩展元数据不是有效 JSON", ex);
    }
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

  private static String truncate(final String value, final int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }

  private static String requireValue(final String value, final String message) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      throw new IllegalArgumentException(message);
    }
    return normalized;
  }

  private static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private static void normalizeLikeQuery(
      final Map<String, String> attributes,
      final String field
  ) {
    String value = attributes.get(field);
    if (value != null && !value.isBlank() && !value.contains(":")) {
      attributes.put(field, "like:" + value.trim());
    }
  }
}
